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

    // Auto-scroll; at the bottom it clicks "next" (custom selector first).
    val AUTO = """
(function(){
  if(window.__autoT){clearInterval(window.__autoT);window.__autoT=null;}
  window.__bgSel=__SEL__;
  window.__autoPause=__PAUSED__;
  if(!__ON__) return;
  var idle=0;
  window.__autoT=setInterval(function(){
    if(window.__autoPause) return;
    var el=document.scrollingElement||document.documentElement;
    var before=el.scrollTop;
    window.scrollBy(0,__PX__);
    if(Math.abs(el.scrollTop-before)<1){idle++;}else{idle=0;}
    if(__CLICK__&&idle>=8){idle=0;if(window.__bgNext)window.__bgNext();}
  },100);
})();
"""

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
}
