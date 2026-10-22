package com.bgbrowser

object Js {
    val PAUSE = "window.__autoPause=true;document.querySelectorAll('audio,video').forEach(function(m){m.pause()});try{speechSynthesis.pause()}catch(e){}"
    val RESUME = "window.__autoPause=false;document.querySelectorAll('audio,video').forEach(function(m){var p=m.play();if(p&&p.catch)p.catch(function(){})});try{speechSynthesis.resume()}catch(e){}"

    // Page always looks visible/focused; shared "next" finder; Chrome 15s TTS workaround; reports speech state to native.
    val KEEPALIVE = """
(function(){
  try{
    Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});
    Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});
    Object.defineProperty(document,'webkitHidden',{get:function(){return false},configurable:true});
    Object.defineProperty(document,'webkitVisibilityState',{get:function(){return 'visible'},configurable:true});
    var stop=function(e){e.stopImmediatePropagation()};
    document.addEventListener('visibilitychange',stop,true);
    document.addEventListener('webkitvisibilitychange',stop,true);
    window.addEventListener('blur',stop,true);
    document.hasFocus=function(){return true};
  }catch(e){}
  if(window.__bgk) return;
  window.__bgk=1;
  window.__bgNext=function(){
    var sel=window.__bgSel;
    if(sel){try{var x=document.querySelector(sel);if(x){x.click();return true;}}catch(e){}}
    var rel=document.querySelector('a[rel=next]'); if(rel){rel.click();return true;}
    var c=document.querySelectorAll('a,button,[role=button]');
    for(var i=0;i<c.length;i++){
      var t=(c[i].innerText||c[i].getAttribute('aria-label')||'').trim().toLowerCase();
      if(/^(next|continue|next page|next chapter)\b|^[›»>→]+${'$'}/.test(t)){c[i].click();return true;}
    }
    return false;
  };
  setInterval(function(){
    try{
      if(window.__bgTts) return;
      var s=window.speechSynthesis;
      if(s&&s.speaking&&!s.paused){s.pause();s.resume();}
    }catch(e){}
  },12000);
  var last='';
  setInterval(function(){
    try{
      if(!window.BgState) return;
      var s=window.speechSynthesis;
      var sp=!!(s&&s.speaking), pa=!!(s&&s.paused);
      var k=sp+','+pa;
      if(k!==last){last=k;BgState.speech(sp,pa);}
    }catch(e){}
  },2000);
})();
"""

    // Stops everything the page is saying or playing (speech is cancelled, not just paused).
    val STOP_ALL = "window.__autoPause=true;try{speechSynthesis.cancel()}catch(e){};document.querySelectorAll('audio,video').forEach(function(m){try{m.pause()}catch(e){}});"

    // Replaces speechSynthesis speak/cancel/pause/resume with Android TextToSpeech (needs the BgTts interface).
    val TTS_SHIM = """
(function(){
  if(window.__bgTts||!window.BgTts||!window.speechSynthesis) return;
  window.__bgTts=1;
  var synth=window.speechSynthesis, queue=[], cur=null, nextId=1, isPaused=false, byId={};
  function emit(u,type,ci,err){
    var ev=null;
    try{
      if(type==='error') ev=new SpeechSynthesisErrorEvent('error',{utterance:u,error:err||'canceled',charIndex:ci||0});
      else ev=new SpeechSynthesisEvent(type,{utterance:u,charIndex:ci||0,charLength:0,elapsedTime:0,name:(type==='boundary'?'word':'')});
    }catch(e){}
    if(ev){try{u.dispatchEvent(ev);return;}catch(e){}}
    var h=u['on'+type];
    if(typeof h==='function'){try{h.call(u,{type:type,target:u,utterance:u,charIndex:ci||0,error:err});}catch(e){}}
  }
  function pump(){
    if(cur||isPaused) return;
    var u=queue.shift(); if(!u) return;
    cur=u; var id=nextId++; byId[id]=u;
    var vn=''; try{vn=(u.voice&&u.voice.name)||'';}catch(e){}
    BgTts.speak(id,String(u.text||''),String(u.lang||''),Number(u.rate)||1,Number(u.pitch)||1,(u.volume===undefined?1:Number(u.volume)),vn);
  }
  window.__bgTtsEvt=function(id,type,ci){
    var u=byId[id]; if(!u) return;
    if(type==='start'){emit(u,'start',0);}
    else if(type==='boundary'){emit(u,'boundary',ci);}
    else if(type==='end'){delete byId[id]; if(cur===u)cur=null; emit(u,'end',0); pump();}
    else if(type==='error'){delete byId[id]; if(cur===u)cur=null; emit(u,'error',0,'synthesis-failed'); pump();}
  };
  function def(name,val){try{Object.defineProperty(synth,name,{value:val,configurable:true,writable:true});}catch(e){}}
  function acc(name,fn){try{Object.defineProperty(synth,name,{get:fn,configurable:true});}catch(e){}}
  def('speak',function(u){queue.push(u);pump();});
  def('cancel',function(){
    var pend=queue.splice(0,queue.length), c=cur;
    cur=null; isPaused=false; byId={};
    try{BgTts.stop();}catch(e){}
    if(c) emit(c,'error',0,'canceled');
    pend.forEach(function(u){emit(u,'error',0,'canceled');});
  });
  def('pause',function(){if(!cur||isPaused)return; isPaused=true; BgTts.pause(); emit(cur,'pause',0);});
  def('resume',function(){if(!isPaused)return; isPaused=false; if(cur){BgTts.resume(); emit(cur,'resume',0);} else pump();});
  acc('speaking',function(){return !!cur;});
  acc('pending',function(){return queue.length>0;});
  acc('paused',function(){return isPaused;});
})();
"""

    // Tor tabs: no WebRTC (it can leak the real IP outside the proxy).
    val NO_WEBRTC = """
(function(){
  ['RTCPeerConnection','webkitRTCPeerConnection','RTCSessionDescription','RTCIceCandidate','RTCDataChannel'].forEach(function(n){
    try{Object.defineProperty(window,n,{value:undefined,configurable:false,writable:false});}catch(e){}
  });
  try{
    if(navigator.mediaDevices){
      navigator.mediaDevices.getUserMedia=function(){return Promise.reject(new DOMException('Blocked','NotAllowedError'));};
      navigator.mediaDevices.enumerateDevices=function(){return Promise.resolve([]);};
    }
    navigator.getUserMedia=undefined; navigator.webkitGetUserMedia=undefined;
  }catch(e){}
})();
"""

    // Tor tabs: make every Tor user look alike and close side channels. LVL 0 standard, 1 safer, 2 safest (no JS at all).
    fun torHarden(level: Int): String = "(function(LVL){" + TOR_HARDEN + "})(" + level + ");"

    private val TOR_HARDEN = """
  var dp=function(o,n,v){try{Object.defineProperty(o,n,{get:function(){return v;},configurable:true});}catch(e){}};
  var kill=function(n){try{Object.defineProperty(window,n,{value:undefined,configurable:true,writable:true});}catch(e){}};
  var N=navigator;
  try{
    dp(N,'hardwareConcurrency',2); dp(N,'languages',['en-US','en']); dp(N,'language','en-US');
    dp(N,'platform','Linux armv81'); dp(N,'userAgentData',undefined); dp(N,'webdriver',false);
    ['deviceMemory','getBattery','connection','mozConnection','webkitConnection','getGamepads','bluetooth','usb','serial','hid','xr','gpu','wakeLock'].forEach(function(n){dp(N,n,undefined);});
  }catch(e){}
  ['DeviceMotionEvent','DeviceOrientationEvent','AmbientLightSensor','Accelerometer','Gyroscope','Magnetometer','Sensor','AbsoluteOrientationSensor','RelativeOrientationSensor','LinearAccelerationSensor','GravitySensor','BatteryManager','NetworkInformation'].forEach(kill);
  try{ if(navigator.storage){ navigator.storage.estimate=function(){return Promise.resolve({quota:2147483648,usage:0});}; } }catch(e){}

  // Screen and window size: rounded, so the exact device is not revealed.
  try{
    var rd=function(v){v=v||0;return Math.max(200,Math.round(v/100)*100);};
    var S=window.screen;
    var gw=function(){return rd(window.innerWidth);}, gh=function(){return rd(window.innerHeight);};
    [['width',gw],['availWidth',gw],['height',gh],['availHeight',gh]].forEach(function(p){try{Object.defineProperty(S,p[0],{get:p[1],configurable:true});}catch(e){}});
    dp(S,'availLeft',0); dp(S,'availTop',0); dp(S,'colorDepth',24); dp(S,'pixelDepth',24);
    Object.defineProperty(window,'outerWidth',{get:function(){return window.innerWidth;},configurable:true});
    Object.defineProperty(window,'outerHeight',{get:function(){return window.innerHeight;},configurable:true});
    if(LVL>=1) Object.defineProperty(window,'devicePixelRatio',{get:function(){return 2;},configurable:true});
  }catch(e){}

  // Time zone and locale: always UTC and en-US.
  try{
    var D=Date.prototype;
    D.getTimezoneOffset=function(){return 0;};
    [['getFullYear','getUTCFullYear'],['getMonth','getUTCMonth'],['getDate','getUTCDate'],['getDay','getUTCDay'],['getHours','getUTCHours'],['getMinutes','getUTCMinutes'],['getSeconds','getUTCSeconds'],['getMilliseconds','getUTCMilliseconds'],['setFullYear','setUTCFullYear'],['setMonth','setUTCMonth'],['setDate','setUTCDate'],['setHours','setUTCHours'],['setMinutes','setUTCMinutes'],['setSeconds','setUTCSeconds'],['setMilliseconds','setUTCMilliseconds']].forEach(function(p){D[p[0]]=D[p[1]];});
    var dn=['Sun','Mon','Tue','Wed','Thu','Fri','Sat'], mn=['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'];
    var p2=function(n){return (n<10?'0':'')+n;};
    var dpart=function(u){return dn[u.getUTCDay()]+' '+mn[u.getUTCMonth()]+' '+p2(u.getUTCDate())+' '+u.getUTCFullYear();};
    var tpart=function(u){return p2(u.getUTCHours())+':'+p2(u.getUTCMinutes())+':'+p2(u.getUTCSeconds())+' GMT+0000 (Coordinated Universal Time)';};
    var fmt=function(f){return function(){var t=this.getTime();if(t!==t)return 'Invalid Date';return f(new Date(t));};};
    D.toString=fmt(function(u){return dpart(u)+' '+tpart(u);});
    D.toDateString=fmt(dpart);
    D.toTimeString=fmt(tpart);
    ['toLocaleString','toLocaleDateString','toLocaleTimeString'].forEach(function(n){
      var o=D[n];
      D[n]=function(l,opt){opt=Object.assign({},opt||{});if(!opt.timeZone)opt.timeZone='UTC';return o.call(this,l||'en-US',opt);};
    });
    var IDF=Intl.DateTimeFormat;
    var W=function(l,o){o=Object.assign({},o||{});if(!o.timeZone)o.timeZone='UTC';return new IDF(l||'en-US',o);};
    W.prototype=IDF.prototype; W.supportedLocalesOf=IDF.supportedLocalesOf;
    Intl.DateTimeFormat=W;
  }catch(e){}

  // Canvas readback gets tiny per-session noise, so canvas fingerprints differ every session.
  try{
    var seed=(Math.random()*4294967296)>>>0;
    var rnd=function(){seed=(Math.imul(seed,1664525)+1013904223)>>>0;return seed/4294967296;};
    var CP=CanvasRenderingContext2D.prototype, HP=HTMLCanvasElement.prototype;
    var oGID=CP.getImageData, oTDU=HP.toDataURL, oTB=HP.toBlob;
    var jitter=function(d){for(var i=0;i<d.length;i+=4){if(rnd()<0.08){var k=(rnd()*3)|0;d[i+k]=d[i+k]^1;}}};
    var copy=function(c){
      try{
        var w=c.width,h=c.height;
        if(!w||!h||w*h>4000000)return c;
        var t=document.createElement('canvas');t.width=w;t.height=h;
        var x=t.getContext('2d');x.drawImage(c,0,0);
        var id=oGID.call(x,0,0,w,h);jitter(id.data);x.putImageData(id,0,0);
        return t;
      }catch(e){return c;}
    };
    CP.getImageData=function(){var r=oGID.apply(this,arguments);try{jitter(r.data);}catch(e){}return r;};
    HP.toDataURL=function(){return oTDU.apply(copy(this),arguments);};
    HP.toBlob=function(){return oTB.apply(copy(this),arguments);};
  }catch(e){}

  // WebGL: hide the GPU name. Safer and Safest remove WebGL, WebAssembly and friends.
  try{
    var patchGL=function(P){
      if(!P)return;
      var gp=P.getParameter, ge=P.getExtension;
      P.getParameter=function(p){if(p===37445)return 'Google Inc.';if(p===37446)return 'ANGLE (Generic Renderer)';return gp.apply(this,arguments);};
      P.getExtension=function(n){if(n==='WEBGL_debug_renderer_info')return null;return ge.apply(this,arguments);};
    };
    patchGL(window.WebGLRenderingContext&&WebGLRenderingContext.prototype);
    patchGL(window.WebGL2RenderingContext&&WebGL2RenderingContext.prototype);
  }catch(e){}
  if(LVL>=1){
    try{
      var gc=HTMLCanvasElement.prototype.getContext;
      HTMLCanvasElement.prototype.getContext=function(t){if(/webgl|experimental/i.test(String(t)))return null;return gc.apply(this,arguments);};
    }catch(e){}
    ['WebGLRenderingContext','WebGL2RenderingContext','OffscreenCanvas','WebAssembly','SharedArrayBuffer'].forEach(kill);
  }

  // Coarser timers make timing attacks harder.
  try{
    var q=LVL>=1?100:10, pn=performance.now.bind(performance);
    performance.now=function(){return Math.floor(pn()/q)*q;};
  }catch(e){}

  // No referrers: links and forms are marked noreferrer before they navigate, and document.referrer is empty.
  try{
    var nr=function(e){
      try{
        var el=e.target&&e.target.closest?e.target.closest('a,area,form'):null;
        if(el){var r=(el.getAttribute('rel')||'');if(r.indexOf('noreferrer')<0)el.setAttribute('rel',(r+' noreferrer noopener').trim());}
      }catch(x){}
    };
    ['click','auxclick','touchstart','mousedown','submit'].forEach(function(n){document.addEventListener(n,nr,true);});
    dp(document,'referrer','');
  }catch(e){}
  try{
    var mo=new MutationObserver(function(){
      try{
        if(document.head){
          if(!document.querySelector('meta[name=referrer]')){var m=document.createElement('meta');m.name='referrer';m.content='no-referrer';document.head.insertBefore(m,document.head.firstChild);}
          mo.disconnect();
        }
      }catch(x){}
    });
    mo.observe(document,{childList:true,subtree:true});
  }catch(e){}

  // No push, notifications, payments or service workers.
  dp(N,'serviceWorker',undefined);
  ['Notification','PushManager','PaymentRequest'].forEach(kill);
"""
}
