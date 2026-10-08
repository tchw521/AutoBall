package com.autoball.core.engine

/**
 * JS 宿主桥接层——**全应用唯一的 API 表面定义**（R-001 / R-121）。
 *
 * 此前 API 白名单分裂在三处，加一个 API 要改三遍，漏改就是运行时报
 * "unknown host api"：
 * 1. C++ `autoball_quickjs.cpp` 的 `kHostApis[]`（QuickJS 每个函数一个 magic）
 * 2. `RhinoEngine.BRIDGE`（Rhino 的 prelude 字符串）
 * 3. `JsHost.call()` 的 when 分支
 *
 * 现在收敛为：C++ 只注册**一个**通用 `host(name, ...args)`，
 * 全部 API 名称与包装都在本文件的 JS 字符串里定义。
 * 两个引擎共用同一份 [PRELUDE]，新增 API 只改本文件 + JsHost。
 *
 * 命名空间的取舍：`ab.*` 为主（自有身份），`zdjl.*` 作别名指向同一对象。
 * 这样从自动精灵社区下载的脚本可以直接跑，不用逐个改名。
 */
object JsBridge {

    /**
     * 统一 prelude。
     *
     * 三部分组成：
     * 1. `__call`：把参数序列化后交给宿主的通用 `host` 入口
     * 2. 全局函数：兼容既有脚本（早期脚本直接 `click(x,y)`）
     * 3. `ab` / `zdjl` 命名空间对象
     *
     * 对象型参数（如 findLocation 的查询对象）在 JS 侧先 stringify 成字符串再传——
     * Rhino 的 NativeJavaObject 无法直接映射成 Kotlin 的 JSONObject，
     * 统一走字符串最稳。
     */
    val PRELUDE: String = """
// Promise 垫片（Rhino 1.7.15 没有原生 Promise）。
// 宿主 API 全是同步的，promise 在 return 前必然已定终态，
// 所以 then 回调**同步立即执行**——不改变单线程脚本的语义，只提前时机。
// 有原生 Promise 时（QuickJS）不生效。
(function(g){
 if(typeof g.Promise!=='undefined') return;
 function P(exec){
  var self=this; self._s=0; self._v=null; self._c=[];
  function settle(st,v){
   if(self._s!==0) return;
   self._s=st; self._v=v;
   for(var i=0;i<self._c.length;i++){self._c[i]();}
  }
  try{ exec(function(v){settle(1,v);}, function(e){settle(2,e);}); }
  catch(e){ settle(2,e); }
 }
 P.prototype.then=function(ok,err){
  var self=this;
  return new P(function(res,rej){
   function run(){
    try{
     if(self._s===1){ res(typeof ok==='function'?ok(self._v):self._v); }
     else if(typeof err==='function'){ res(err(self._v)); }
     else { rej(self._v); }
    }catch(e){ rej(e); }
   }
   if(self._s!==0){ run(); } else { self._c.push(run); }
  });
 };
 P.prototype['catch']=function(f){return this.then(null,f);};
 P.resolve=function(v){return new P(function(r){r(v);});};
 P.reject=function(e){return new P(function(r,j){j(e);});};
 P.all=function(arr){
  return new P(function(res,rej){
   var out=[],n=0,i;
   for(i=0;i<arr.length;i++){
    (function(idx){
     P.resolve(arr[idx]).then(function(v){out[idx]=v;if(++n===arr.length)res(out);},rej);
    })(i);
   }
   if(arr.length===0){ res(out); }
  });
 };
 P.race=function(arr){
  return new P(function(res,rej){
   for(var i=0;i<arr.length;i++){ P.resolve(arr[i]).then(res,rej); }
  });
 };
 g.Promise=P;
})(typeof globalThis!=='undefined'?globalThis:(function(){return this;})());

function __call(name){
  var a=[];for(var i=1;i<arguments.length;i++){a.push(arguments[i]);}
  var r=__host(name, JSON.stringify(a));
  var o;try{o=JSON.parse(r);}catch(e){o=eval('('+r+')');}
  if(!o.ok){throw new Error(o.error||'host error');}
  return o.value;}

function click(x,y){return __call('click',x,y);}
function press(x,y,d){return __call('press',x,y,d);}
function longClick(x,y,d){return __call('longClick',x,y,d==undefined?600:d);}
function swipe(x1,y1,x2,y2,d){return __call('swipe',x1,y1,x2,y2,d==undefined?300:d);}
function sleep(ms){return __call('sleep',ms);}
function globalAction(n){return __call('globalAction',n);}
function key(c){return __call('key',c);}
function input(t){return __call('input',t);}
function openApp(p){return __call('openApp',p);}
function toast(t){return __call('toast',t);}
function screenshot(){return __call('screenshot');}
function findNode(t){return __call('findNode',t);}
function clickText(t){return __call('clickText',t);}
function setVar(k,v){return __call('setVar',k,v);}
function getVar(k){return __call('getVar',k);}
function log(m){return __call('log',m);}
function stop(){return __call('stop');}
function alert(m,o){return __call('alert',m,o);}
function confirm(m,o){return __call('confirm',m,o);}
function prompt(m,d,o){return __call('prompt',m,d==undefined?'':d,o);}
function toast(m,d){return __call('toast',m,d==undefined?0:d);}

var ab={
 click:function(x,y,d){return __call('click',x,y,d);},
 press:function(x,y,d){return __call('press',x,y,d);},
 longClick:function(x,y,d){return __call('longClick',x,y,d);},
 swipe:function(x1,y1,x2,y2,d){return __call('swipe',x1,y1,x2,y2,d);},
 sleep:function(ms){return __call('sleep',ms);},
 globalAction:function(n){return __call('globalAction',n);},
 key:function(c){return __call('key',c);},
 input:function(t){return __call('input',t);},
 openApp:function(p){return __call('openApp',p);},

 screenshot:function(){return __call('screenshot');},
 clickText:function(t){return __call('clickText',t);},
 getVars:function(){return __call('getVars');},
 findLocation:function(q,a){return __call('findLocation',__s(q),!!a);},
 getScreenColor:function(x,y){return __call('getScreenColor',x,y);},
 getScreenAreaColors:function(q){return __call('getScreenAreaColors',__s(q));},
 readFile:function(p){return __call('readFile',p);},
 writeFile:function(p,c){return __call('writeFile',p,c);},
 appendFile:function(p,c){return __call('appendFile',p,c);},
 getStorage:function(k,sc){return __call('getStorage',k,sc);},
 setStorage:function(k,v,sc){return __call('setStorage',k,v,sc);},
 removeStorage:function(k,sc){return __call('removeStorage',k,sc);},
 gesture:function(){return __call.apply(null,['gesture'].concat(__arr(arguments)));},
 gestures:function(){return __call.apply(null,['gestures'].concat(__arr(arguments)));},
 touchDown:function(x,y){return __call('touchDown',x,y);},
 touchMove:function(x,y,d){return __call('touchMove',x,y,d);},
 touchUp:function(d){return __call('touchUp',d);},
 runAction:function(a){return __call('runAction',__s(a));},
 check:function(c){return __call('check',__s(c));},
 log:function(m){return __call('log',m);},
 stop:function(){return __call('stop');},
 isCanceled:function(){return __call('isCanceled');},
 backend:function(){return __call('backend');},

 alert:function(m,o){return __call('alert',m,o);},
 confirm:function(m,o){return __call('confirm',m,o);},
 prompt:function(m,d,o){return __call('prompt',m,d==undefined?'':d,o);},
 select:function(o){return __call('select',__s(o));},
 toast:function(m,d){return __call('toast',m,d==undefined?0:d);},

 getVar:function(n,sc){return __call('getVar',n,sc);},
 setVar:function(n,v,sc){return __call('setVar',n,v,sc);},
 deleteVar:function(n,sc){return __call('deleteVar',n,sc);},
 clearVars:function(sc){return __call('clearVars',sc);},
 printVars:function(){return __call('printVars');},

 keyDown:function(k){return __call('keyDown',k);},
 keyUp:function(k){return __call('keyUp',k);},
 keyPress:function(){return __call.apply(null,['keyPress'].concat(__arr(arguments)));},
 getClipboard:function(){return __call('getClipboard');},
 setClipboard:function(t){return __call('setClipboard',t);},
 getDeviceInfo:function(){return __call('getDeviceInfo');},
 getAppVersion:function(){return __call('getAppVersion');},
 getInstalledAppInfo:function(p){return __call('getInstalledAppInfo',p);},
 vibrator:function(ms,a){return __call('vibrator',ms,a);},
 requestUrl:function(o){return __call('requestUrl',__s(o));},
 ocr:function(o){return __call('ocr',__s(o));},
 recognitionScreen:function(o){return __call('recognitionScreen',__s(o));},
 findNode:function(q,o){return __call('findNode',__s(q),__s(o));},
 require:function(n){return require(n);},
 playMedia:function(p,o){return __call('playMedia',p,__s(o));},
 getMousePosition:function(){return __call('getMousePosition');}
};

// console：全部写进运行日志（自动精灵里是日志面板）
var __t={};
var console={
 log:function(){__cl('log',arguments);},
 info:function(){__cl('info',arguments);},
 debug:function(){__cl('debug',arguments);},
 verbose:function(){__cl('verbose',arguments);},
 warn:function(){__cl('warn',arguments);},
 error:function(){__cl('error',arguments);},
 assert:function(c,m){if(!c)__cl('error',[m]);},
 dir:function(o){__cl('log',[__s(o)]);},
 time:function(l){__t[l]=Date.now();},
 timeLog:function(l){__cl('log',[l+': '+(Date.now()-__t[l])+'ms']);},
 timeEnd:function(l){__cl('log',[l+': '+(Date.now()-__t[l])+'ms']);delete __t[l];},
 clear:function(){__call('console','clear');},
 show:function(){return __call('console','show');},
 hide:function(){return __call('console','hide');}
};
function __cl(lv,a){var m=[];for(var i=0;i<a.length;i++){m.push(typeof a[i]==='string'?a[i]:__s(a[i]));}
 __call('console',lv,m.join(' '));}

// 异步变体：宿主调用是**同步阻塞**的（同一个 OS 线程里跑完才返回），
// 所以这里按同步别名实现并直接返回值。
// 不是偷懒——若返回真 Promise，Promise.all([...Async, ...Async]) 看着像并发，
// 实际仍是顺序执行，会误导脚本作者。诚实返回当前行为（R-003）。
// 脚本自身的 Promise 链（then / await）已由宿主排空队列后正常执行。
(function(){
 var asyncNames=['sleepAsync','alertAsync','confirmAsync','promptAsync','selectAsync',
  'clickAsync','longClickAsync','swipeAsync','gestureAsync','gesturesAsync',
  'runActionAsync','findLocationAsync','findNodeAsync','recognitionScreenAsync',
  'getScreenColorAsync','getScreenAreaColorsAsync','requestUrlAsync',
  'writeFileAsync','appendFileAsync','readFileAsync','playMediaAsync',
  'touchDownAsync','touchMoveAsync','touchUpAsync','ocrAsync','vibratorAsync'];
 for(var i=0;i<asyncNames.length;i++){
  (function(name){
   var sync=name.replace(/Async$/,'');
   if(typeof ab[sync]!=='function') return;
   ab[name]=function(){return ab[sync].apply(ab,arguments);};
  })(asyncNames[i]);
 }
})();
/**
 * require：加载本地 JS 模块（CommonJS 简化版）。
 *
 * 路径走与 readFile 相同的私有目录映射，所以 require('utils.js')
 * 读的是脚本私有目录里的文件。
 *
 * 只支持**同步返回 exports** 的模块——不支持 npm 包与网络加载，
 * 本应用不联网、也没有模块仓库，硬做只会给出能写不能跑的假能力（R-003）。
 */
function require(name){
 var code=__call('readFile',name);
 var m={exports:{}};
 (function(module,exports){ eval(code); })(m,m.exports);
 return m.exports;
}
function __s(q){return (typeof q==='string')?q:JSON.stringify(q);}
function __arr(a){var r=[];for(var i=0;i<a.length;i++){r.push(a[i]);}return r;}
var zdjl=ab;
""".trimIndent()

    /**
     * 把用户脚本包成 async IIFE（**仅 QuickJS 可用**）。
     *
     * 自动精灵文档里的示例大量使用顶层 `await`（`await zdjl.requestUrlAsync(...)`），
     * 而 QuickJS 的 JS_Eval 在 GLOBAL 模式下不支持顶层 await——不包装会直接语法错误。
     *
     * 不能用 Rhino：1.7.15 不支持 async/await，套上去会让所有脚本语法错误。
     * 代价：报错行号偏移 1 行。
     */
    fun wrapAsync(code: String): String = "(async function(){\n$code\n})()"
}
