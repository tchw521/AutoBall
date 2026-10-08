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
 toast:function(t){return __call('toast',t);},
 screenshot:function(){return __call('screenshot');},
 findNode:function(t){return __call('findNode',t);},
 clickText:function(t){return __call('clickText',t);},
 setVar:function(k,v){return __call('setVar',k,v);},
 getVar:function(k){return __call('getVar',k);},
 getVars:function(){return __call('getVars');},
 findLocation:function(q){return __call('findLocation',__s(q));},
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
 backend:function(){return __call('backend');}
};
function __s(q){return (typeof q==='string')?q:JSON.stringify(q);}
function __arr(a){var r=[];for(var i=0;i<a.length;i++){r.push(a[i]);}return r;}
var zdjl=ab;
""".trimIndent()
}
