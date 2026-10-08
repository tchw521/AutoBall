//
// AutoBall × QuickJS JNI 桥接
//
// 设计要点：
// 1) 每个 JsRunner 独占一个线程 / JSRuntime / JSContext，绝不跨线程共享；
// 2) 宿主函数通过"方法名 + JSON 参数数组"回调到 Kotlin，返回值用 JSON 解析回 JS；
// 3) 三层超时：JS_SetInterruptHandler 处理死循环，宿主 sleep 由 Java 侧截断，
//    执行结果回传 name/message/stack。
//
// 当 AUTOBALL_HAS_QUICKJS=0（未拉取 QuickJS 源码）时，本文件编译为 stub，
// JNI 符号保持一致，Kotlin 侧只是拿不到 JS 能力。
//

#include <jni.h>
#include <string>
#include <vector>
#include <cstdlib>
#include <cstring>
#include <cstdio>

#if AUTOBALL_HAS_QUICKJS
#include "quickjs.h"
#include <chrono>
#endif

extern "C" {

#if AUTOBALL_HAS_QUICKJS

// ---------------- 宿主回调上下文 ----------------

struct HostCtx {
    JNIEnv *env;
    jobject callback;      // HostCb 实例（eval 调用帧内的局部引用）
    jmethodID callMethod;
};

static HostCtx g_host{nullptr, nullptr, nullptr};

static std::chrono::steady_clock::time_point g_deadline;
static volatile bool g_interrupted = false;

static std::string jsonEscape(const std::string &s) {
    std::string out;
    out.reserve(s.size() + 8);
    for (char c : s) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default:
                if ((unsigned char) c < 0x20) {
                    char buf[8];
                    snprintf(buf, sizeof(buf), "\\u%04x", (unsigned char) c);
                    out += buf;
                } else {
                    out += c;
                }
        }
    }
    return out;
}

static void appendJsValueAsJson(JSContext *ctx, std::string &out, JSValueConst v) {
    if (JS_IsNumber(v)) {
        double d = 0;
        JS_ToFloat64(ctx, &d, v);
        char buf[64];
        snprintf(buf, sizeof(buf), "%.17g", d);
        out += buf;
    } else if (JS_IsBool(v)) {
        out += JS_ToBool(ctx, v) ? "true" : "false";
    } else if (JS_IsNull(v) || JS_IsUndefined(v)) {
        out += "null";
    } else if (JS_IsString(v)) {
        const char *s = JS_ToCString(ctx, v);
        if (s == nullptr) { out += "null"; return; }
        out += "\"";
        out += jsonEscape(std::string(s));
        out += "\"";
        JS_FreeCString(ctx, s);
    } else {
        JSValue j = JS_JSONStringify(ctx, v, JS_UNDEFINED, JS_UNDEFINED);
        if (JS_IsException(j)) {
            out += "null";
        } else {
            const char *s = JS_ToCString(ctx, j);
            out += (s != nullptr) ? s : "null";
            if (s != nullptr) JS_FreeCString(ctx, s);
        }
        JS_FreeValue(ctx, j);
    }
}

// ---------- Promise 微任务队列（R-127）----------

// 单次执行最多排空多少个微任务。
// 脚本可以写出"then 里再 new Promise"的链，队列理论上永不空；
// deadline 只拦 CPU 时间、不拦 job 数量，所以再设一道数量上限，
// 否则一个自我 reschedule 的链就能把线程吃满。
#define AUTOBALL_MAX_JOBS 100000

/**
 * 排空 Promise 微任务队列。
 *
 * QuickJS **不会自动执行** Promise 回调，必须由宿主调用 JS_ExecutePendingJob。
 * 不排空的后果：脚本里 .then / await 之后的代码永远不执行，
 * 而且没有任何报错——表现为"脚本跑一半就没动静了"，是最难排查的一类缺陷。
 *
 * @return false 表示 job 抛异常或未捕获的 rejection，错误已写入 errOut
 */
static bool drainJobs(JSRuntime *rt, std::string &errOut) {
    int guard = 0;
    for (;;) {
        JSContext *jobCtx = nullptr;
        int r = JS_ExecutePendingJob(rt, &jobCtx);
        if (r == 0) return true;           // 队列已空
        if (r < 0) {
            if (jobCtx == nullptr) { errOut = "Promise 回调执行失败"; return false; }
            JSValue e = JS_GetException(jobCtx);
            const char *m = JS_ToCString(jobCtx, e);
            errOut = (m != nullptr) ? m : "Promise 回调抛出异常";
            if (m != nullptr) JS_FreeCString(jobCtx, m);
            JS_FreeValue(jobCtx, e);
            return false;
        }
        if (g_interrupted) return true;    // 超时/被停止：交给外层统一处理
        if (++guard >= AUTOBALL_MAX_JOBS) {
            errOut = "Promise 任务过多（超过 100000 个），已中止";
            return false;
        }
    }
}

/**
 * 展开顶层 Promise：把 JS_Eval 返回的 promise 换成它的终值。
 *
 * 脚本最后一句是 `await xxx` 或返回 promise 时，直接取值只能拿到一个空对象，
 * 脚本作者会以为"代码没生效"。
 *
 * 关于 JS_PromiseState 的安全性：本引擎没有 JS_IsPromise（那是 quickjs-ng 的扩展），
 * 先用 thenable 粗筛；对非 promise 对象 JS_GetOpaque 不匹配即返回 NULL，
 * JS_PromiseState 会返回 PENDING，不会崩溃——最坏情况是误报"等待未完成"，
 * 属于明确报错而非静默，符合 R-003。
 *
 * @return false 表示被拒绝或永远 pending，错误已写入 errOut
 *
 * 若编译报 `undefined reference to JS_PromiseState`（quickjs 版本过旧），
 * 可改用两段式：先注册 then 把结果写进全局对象 → drain → 再读全局对象，
 * 只依赖 JS_GetGlobalObject / JS_GetPropertyStr / JS_Call 这些基础 API。
 */
static bool settleTopPromise(JSContext *ctx, JSValue *pv, std::string &errOut) {
    JSValue v = *pv;
    if (!JS_IsObject(v)) return true;
    JSValue then = JS_GetPropertyStr(ctx, v, "then");
    bool isThenable = JS_IsFunction(ctx, then);
    JS_FreeValue(ctx, then);
    if (!isThenable) return true;

    JSPromiseStateEnum st = JS_PromiseState(ctx, v);
    if (st == JS_PROMISE_FULFILLED) {
        JSValue r = JS_PromiseResult(ctx, v);
        JS_FreeValue(ctx, v);
        *pv = r;
        return true;
    }
    if (st == JS_PROMISE_REJECTED) {
        JSValue r = JS_PromiseResult(ctx, v);
        const char *m = JS_ToCString(ctx, r);
        errOut = (m != nullptr) ? m : "Promise 被拒绝";
        if (m != nullptr) JS_FreeCString(ctx, m);
        JS_FreeValue(ctx, r);
        return false;
    }
    // PENDING：宿主 API 是同步的，*Async 也按同步执行，
    // 所以"永远 pending"只可能是脚本在等一个外部事件——如实告知，不要干等
    errOut = "脚本在等待一个不会完成的 Promise（本引擎宿主 API 为同步执行，"
             "*Async 已按同步别名实现；请检查是否依赖了真正的异步事件）";
    return false;
}

// 通用转发入口：JS 侧的 __host('click', ...) 走这里。
// 具体 API 名称与包装全部定义在 Kotlin 的 JsBridge.PRELUDE——
// 新增 API 不必再改本文件（此前每个 API 都要在这里加一个字符串）。
// "host" 放末尾便于阅读；magic 由数组下标自动推导，位置无要求。
static const char *kHostApis[] = {
        "click", "press", "longClick", "swipe", "sleep", "globalAction",
        "key", "input", "openApp", "toast", "screenshot", "findNode",
        "clickText", "setVar", "getVar", "log", "stop", "isCanceled", "backend",
        "host"
};

static JSValue js_dispatch(JSContext *ctx, JSValueConst this_val, int argc,
                           JSValueConst *argv, int magic) {
    if (magic < 0 || magic >= (int) (sizeof(kHostApis) / sizeof(kHostApis[0]))) {
        return JS_ThrowTypeError(ctx, "unknown host api");
    }
    if (g_host.env == nullptr || g_host.callback == nullptr) {
        return JS_ThrowInternalError(ctx, "host callback unavailable");
    }

    std::string args = "[";
    for (int i = 0; i < argc; i++) {
        if (i > 0) args += ",";
        appendJsValueAsJson(ctx, args, argv[i]);
    }
    args += "]";

    JNIEnv *env = g_host.env;
    jstring jName = env->NewStringUTF(kHostApis[magic]);
    jstring jArgs = env->NewStringUTF(args.c_str());
    jobject jRet = env->CallObjectMethod(g_host.callback, g_host.callMethod, jName, jArgs);

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        env->DeleteLocalRef(jName);
        env->DeleteLocalRef(jArgs);
        return JS_ThrowInternalError(ctx, "host threw exception");
    }

    std::string ret;
    if (jRet != nullptr) {
        const char *s = env->GetStringUTFChars((jstring) jRet, nullptr);
        if (s != nullptr) { ret = s; env->ReleaseStringUTFChars((jstring) jRet, s); }
        env->DeleteLocalRef(jRet);
    }
    env->DeleteLocalRef(jName);
    env->DeleteLocalRef(jArgs);

    if (ret.empty()) return JS_UNDEFINED;

    JSValue parsed = JS_ParseJSON(ctx, ret.c_str(), ret.size(), "<host>");
    if (JS_IsException(parsed)) {
        JS_FreeValue(ctx, parsed);
        return JS_ThrowInternalError(ctx, "invalid host result");
    }

    // {ok:bool, value:*, error:string}
    JSValue okProp = JS_GetPropertyStr(ctx, parsed, "ok");
    bool ok = JS_ToBool(ctx, okProp) != 0;
    JS_FreeValue(ctx, okProp);

    if (!ok) {
        JSValue errProp = JS_GetPropertyStr(ctx, parsed, "error");
        const char *msg = JS_ToCString(ctx, errProp);
        JSValue err = JS_ThrowInternalError(ctx, "%s", msg != nullptr ? msg : "host error");
        if (msg != nullptr) JS_FreeCString(ctx, msg);
        JS_FreeValue(ctx, errProp);
        JS_FreeValue(ctx, parsed);
        return err;
    }

    JSValue val = JS_GetPropertyStr(ctx, parsed, "value");
    JS_FreeValue(ctx, parsed);
    return val;
}

static int js_interrupt_handler(JSRuntime *rt, void *opaque) {
    (void) rt;
    (void) opaque;
    if (g_interrupted) return 1;
    if (std::chrono::steady_clock::now() > g_deadline) return 1;
    return 0;
}

static void registerHostApis(JSContext *ctx) {
    JSValue global = JS_GetGlobalObject(ctx);
    int n = (int) (sizeof(kHostApis) / sizeof(kHostApis[0]));
    for (int i = 0; i < n; i++) {
        JSValue fn = JS_NewCFunctionMagic(ctx, js_dispatch, kHostApis[i], 3,
                                          JS_CFUNC_generic_magic, i);
        JS_SetPropertyStr(ctx, global, kHostApis[i], fn);
    }
    JS_FreeValue(ctx, global);
}

#endif // AUTOBALL_HAS_QUICKJS

// ---------------- JNI 入口 ----------------

JNIEXPORT jlong JNICALL
Java_com_autoball_core_engine_quickjs_QuickJsEngine_nativeCreate(JNIEnv *env, jobject thiz) {
#if AUTOBALL_HAS_QUICKJS
    (void) env;
    (void) thiz;
    JSRuntime *rt = JS_NewRuntime();
    if (rt == nullptr) return 0;
    JSContext *ctx = JS_NewContext(rt);
    if (ctx == nullptr) { JS_FreeRuntime(rt); return 0; }
    registerHostApis(ctx);
    return (jlong) (intptr_t) ctx;
#else
    (void) env;
    (void) thiz;
    return 0;
#endif
}

JNIEXPORT void JNICALL
Java_com_autoball_core_engine_quickjs_QuickJsEngine_nativeDestroy(JNIEnv *env, jobject thiz,
                                                                  jlong handle) {
#if AUTOBALL_HAS_QUICKJS
    (void) env;
    (void) thiz;
    if (handle == 0) return;
    JSContext *ctx = (JSContext *) (intptr_t) handle;
    JSRuntime *rt = JS_GetRuntime(ctx);
    JS_FreeContext(ctx);
    JS_FreeRuntime(rt);
#else
    (void) env;
    (void) thiz;
    (void) handle;
#endif
}

JNIEXPORT void JNICALL
Java_com_autoball_core_engine_quickjs_QuickJsEngine_nativeInterrupt(JNIEnv *env, jobject thiz,
                                                                    jlong handle) {
#if AUTOBALL_HAS_QUICKJS
    (void) env;
    (void) thiz;
    (void) handle;
    g_interrupted = true;
#else
    (void) env;
    (void) thiz;
    (void) handle;
#endif
}

JNIEXPORT jstring JNICALL
Java_com_autoball_core_engine_quickjs_QuickJsEngine_nativeEval(JNIEnv *env, jobject thiz,
                                                               jlong handle, jstring code,
                                                               jobject callback, jlong timeoutMs) {
#if AUTOBALL_HAS_QUICKJS
    (void) thiz;
    if (handle == 0) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"QuickJS 上下文未创建\"}");
    }
    JSContext *ctx = (JSContext *) (intptr_t) handle;
    JSRuntime *rt = JS_GetRuntime(ctx);

    const char *cstr = env->GetStringUTFChars(code, nullptr);
    std::string src = (cstr != nullptr) ? cstr : "";
    if (cstr != nullptr) env->ReleaseStringUTFChars(code, cstr);

    // 准备宿主回调
    jclass cbCls = env->GetObjectClass(callback);
    jmethodID callMethod = env->GetMethodID(cbCls, "call",
                                            "(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
    env->DeleteLocalRef(cbCls);
    if (callMethod == nullptr) {
        return env->NewStringUTF("{\"ok\":false,\"error\":\"宿主回调接口不匹配\"}");
    }

    g_host.env = env;
    g_host.callback = callback;
    g_host.callMethod = callMethod;
    g_interrupted = false;
    g_deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);
    JS_SetInterruptHandler(rt, js_interrupt_handler, nullptr);

    JSValue ret = JS_Eval(ctx, src.c_str(), src.size(), "<script>", JS_EVAL_TYPE_GLOBAL);
    std::string out;

    // ---- R-127：先排空 Promise 队列，再读结果 ----
    // 顺序不能反：.then 回调可能修改 ret 指向的对象。
    // 两段都把失败转成异常，交给下面统一的异常处理分支，避免重复写一份序列化。
    std::string asyncErr;
    if (!JS_IsException(ret)) {
        if (!drainJobs(rt, asyncErr)) {
            JS_FreeValue(ctx, ret);
            ret = JS_ThrowInternalError(ctx, "%s", asyncErr.c_str());
        } else if (g_interrupted) {
            JS_FreeValue(ctx, ret);
            ret = JS_ThrowInternalError(ctx, "脚本执行超时或被停止");
        } else if (!settleTopPromise(ctx, &ret, asyncErr)) {
            JS_FreeValue(ctx, ret);
            ret = JS_ThrowInternalError(ctx, "%s", asyncErr.c_str());
        }
    }

    if (JS_IsException(ret)) {
        JSValue err = JS_GetException(ctx);
        const char *msg = JS_ToCString(ctx, err);
        std::string message = (msg != nullptr) ? msg : "脚本异常";
        if (msg != nullptr) JS_FreeCString(ctx, msg);

        std::string stack;
        if (JS_IsError(ctx, err)) {
            JSValue st = JS_GetPropertyStr(ctx, err, "stack");
            const char *s = JS_ToCString(ctx, st);
            if (s != nullptr) { stack = s; JS_FreeCString(ctx, s); }
            JS_FreeValue(ctx, st);
        }
        JS_FreeValue(ctx, err);

        out = "{\"ok\":false,\"error\":\"" + jsonEscape(message) + "\"";
        if (!stack.empty()) out += ",\"stack\":\"" + jsonEscape(stack) + "\"";
        out += "}";
    } else {
        std::string value;
        appendJsValueAsJson(ctx, value, ret);
        out = "{\"ok\":true,\"value\":" + value + "}";
    }

    JS_FreeValue(ctx, ret);
    g_host.env = nullptr;
    g_host.callback = nullptr;
    g_host.callMethod = nullptr;

    return env->NewStringUTF(out.c_str());
#else
    (void) thiz;
    (void) handle;
    (void) callback;
    (void) timeoutMs;
    if (code != nullptr) { /* 未使用：stub 模式下不执行 */ }
    return env->NewStringUTF(
            "{\"ok\":false,\"error\":\"QuickJS 未内置（stub 构建），请改用动作流或 Rhino 构建\"}");
#endif
}

} // extern "C"
