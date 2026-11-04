package com.androidperformancestudio.desktop

/** Companion uses host-issued stable IDs; it explicitly does not guess IDs from Firefox labels. */
internal fun simpleperfWebBridgeAdapter(token: String): String = """
    (() => {
      'use strict';
      const token = '$token';
      let context = null, seq = 0, active = false;
      const headers = {'X-APS-Bridge-Token':token,'Content-Type':'application/json'};
      const box = document.createElement('aside');
      box.id = 'aps-canonical-ai';
      box.style.cssText = 'position:fixed;right:12px;bottom:12px;z-index:2147483647;background:#20252c;color:#fff;padding:12px;border:1px solid #65788a;border-radius:8px;max-width:360px;font:13px sans-serif';
      const title = document.createElement('strong'); title.textContent = 'Compose AI · canonical host selection'; box.append(title);
      const note = document.createElement('p'); note.textContent = 'Firefox canvas selection mapping is unavailable. Choose a canonical host node below; analysis and source authorization open in Compose.'; box.append(note);
      const panel = document.createElement('select'), node = document.createElement('select');
      for (const value of ['FLAME_GRAPH','CALL_TREE','STACK_CHART']) { const o=document.createElement('option');o.value=value;o.textContent=value;panel.append(o); }
      node.style.maxWidth='330px';box.append(panel,node);
      const button=document.createElement('button');button.textContent='Analyze in Compose';
      const cancel=document.createElement('button');cancel.textContent='Cancel';
      const status=document.createElement('p');box.append(button,cancel,status);document.body.append(box);
      const show = value => {status.textContent = value;};
      function refresh() {
        node.replaceChildren();
        if (!context) return;
        for (const item of context.selections.filter(x=>x.panel===panel.value)) {const o=document.createElement('option');o.value=item.id;o.textContent=item.label;node.append(o);}
        button.disabled=!node.options.length;
      }
      async function send(cancelled, keepalive=false) {
        if (!context) return;
        const event={protocolVersion:1,sessionId:context.sessionId,reportGeneration:context.reportGeneration,pageId:context.pageId,sequence:++seq,
          panel:cancelled?null:panel.value,callNodeId:!cancelled&&panel.value!=='STACK_CHART'?node.value:null,stackBlockId:!cancelled&&panel.value==='STACK_CHART'?node.value:null,cancel:cancelled};
        active=!cancelled;
        try {const r=await fetch('/aps-ai/event',{method:'POST',headers,body:JSON.stringify(event),cache:'no-store',keepalive});const result=await r.json();show(result.status+' · '+result.code);}
        catch (_) {show('unavailable · E_BRIDGE_DISCONNECTED');}
      }
      panel.addEventListener('change',refresh);button.addEventListener('click',()=>send(false));cancel.addEventListener('click',()=>send(true));
      window.addEventListener('pagehide',()=>{if(active)send(true,true);});
      fetch('/aps-ai/context',{headers,cache:'no-store'}).then(r=>{if(!r.ok)throw Error();return r.json();}).then(value=>{context=value;refresh();show('Ready · '+value.selections.length+' canonical selections');}).catch(()=>show('unavailable · E_BRIDGE_CONTEXT'));
      setInterval(async()=>{if(!context||!active)return;try{const r=await fetch('/aps-ai/result',{headers,cache:'no-store'});const v=await r.json();show(v.status+' · '+v.code);if(['succeeded','failed','cancelled','stale'].includes(v.status))active=false;}catch(_){active=false;show('unavailable · E_BRIDGE_DISCONNECTED');}},1000);
    })();
""".trimIndent()
