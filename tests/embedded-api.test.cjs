'use strict';
const {test}=require('node:test');
const assert=require('node:assert/strict');
const path=require('node:path');
const {start}=require('../app/src/main/node/bootstrap.cjs');
const config={token:'a'.repeat(64),guid:'b'.repeat(32),dev:'C'.repeat(10),uuid:'d'.repeat(32)};
function fixture(overrides={}){
  const calls=[];
  return {calls,options:{util:{calculateMid:()=> 'fixture-mid'},createRequest:()=>{},moduleLoader:name=>async params=>{
    calls.push({name,params});
    if(overrides[name])return overrides[name](params,calls.length);
    return {status:200,body:{status:1,data:{name}},cookie:[]};
  }}};
}
async function connection(fixture,run){const value=await start(config,fixture.options);try{await run(`http://127.0.0.1:${value.port}`,value.server);}finally{await new Promise(resolve=>value.server.close(resolve));}}
const headers={'X-Echo-Token':config.token};

test('account playlists request a full snapshot instead of a hardcoded incremental version',async()=>{
  const captured=[];const options={util:{calculateMid:()=> 'fixture-mid'},moduleLoader:name=>require('../../android-api/module/'+name+'.js'),createRequest:async value=>{captured.push(value);return {status:200,body:{status:1,data:{info:[]}},cookie:[]};}};
  await connection({options},async(base)=>{
    await fetch(base+'/user/playlist',{headers});assert.equal(captured[0].data.total_ver,0);
    await fetch(base+'/user/playlist?total_ver=25',{headers});assert.equal(captured[1].data.total_ver,25);
  });
});

test('lyric service success 200 is normalized before original LRC decoding, but account failures remain errors',async()=>{
  const raw='[00:01.00]新地球';
  const options={util:{calculateMid:()=> 'fixture-mid'},
    moduleLoader:name=>name==='lyric'?require('../../android-api/module/lyric.js'):async(params,request)=>request({}),
    createRequest:async()=>{throw {status:502,body:{status:200,error_code:200,content:Buffer.from(raw).toString('base64'),contenttype:1}};}};
  await connection({options},async(base)=>{
    let response=await fetch(base+'/lyric?id=1&accesskey=test&fmt=lrc&decode=true',{headers});
    assert.equal(response.status,200);let body=await response.json();assert.equal(body.error_code,0);assert.equal(body.decodeContent,raw);
    response=await fetch(base+'/user/playlist',{headers});assert.equal(response.status,502);assert.equal((await response.json()).error_code,200);
  });
});

test('lyric search 200 with an empty candidate array is success, rejected status remains failure',async()=>{
  let failure=false;
  const options={util:{calculateMid:()=> 'fixture-mid'},moduleLoader:()=>async(p,request)=>request({}),createRequest:async()=>{throw {status:502,body:{status:failure?0:200,error_code:200,candidates:[]}};}};
  await connection({options},async(base)=>{
    let response=await fetch(base+'/search/lyric?keywords=test',{headers});assert.equal(response.status,200);assert.deepEqual((await response.json()).candidates,[]);
    failure=true;response=await fetch(base+'/search/lyric?keywords=test',{headers});assert.equal(response.status,502);assert.equal((await response.json()).status,0);
  });
});
test('listener is loopback-only, ephemeral, authenticated and healthy',async()=>{
  await connection(fixture(),async(base,server)=>{
    assert.equal(server.address().address,'127.0.0.1');assert.ok(server.address().port>0);
    assert.equal((await fetch(base+'/__health')).status,403);
    assert.equal((await fetch(base+'/__health',{headers:{'X-Echo-Token':'wrong'}})).status,403);
    const response=await fetch(base+'/__health',{headers});assert.equal(response.status,200);const body=await response.json();assert.equal(body.status,1);assert.equal(body.version,3);assert.equal(body.runtime,process.versions.node);assert.equal(response.headers.get('cache-control'),'no-store');assert.equal(response.headers.get('access-control-allow-origin'),null);
  });
});
test('only explicit API routes and GET are reachable; no files or generic proxy',async()=>{
  const f=fixture();await connection(f,async(base)=>{
    for(const route of ['/','/docs','/package.json','/node_modules/axios/package.json','/proxy','/../package.json','/__proto__'])assert.equal((await fetch(base+route,{headers})).status,404);
    assert.equal((await fetch(base+'/captcha/sent',{method:'POST',headers})).status,405);assert.equal(f.calls.length,0);
  });
});
test('query, device identity and account header are merged with current-account priority',async()=>{
  const f=fixture();await connection(f,async(base)=>{
    const response=await fetch(base+'/search?keywords=hello%20world&cookie='+encodeURIComponent('token=query-account;qrsig=qr-cookie'),{headers:{...headers,Cookie:'dfid=real-device',Authorization:'token=current-account;userid=123'}});
    assert.equal(response.status,200);const call=f.calls[0];assert.equal(call.name,'search');assert.equal(call.params.keywords,'hello world');assert.equal(call.params.cookie.token,'current-account');assert.equal(call.params.cookie.userid,'123');assert.equal(call.params.cookie.dfid,'real-device');assert.equal(call.params.cookie.qrsig,'qr-cookie');assert.equal(call.params.cookie.KUGOU_API_PLATFORM,'lite');assert.equal(call.params.cookie.KUGOU_API_MID,'fixture-mid');assert.ok(response.headers.get('set-cookie').includes('KUGOU_API_GUID='));
  });
});
test('QR polling is never cached and follows waiting, scanned and authorized responses',async()=>{
  let step=0;const statuses=[1,2,4];const f=fixture({login_qr_check:()=>({status:200,body:{status:1,data:{status:statuses[step++]}},cookie:[]})});
  await connection(f,async(base)=>{for(const expected of statuses){const response=await fetch(base+'/login/qr/check?key=fixture-key',{headers});assert.equal((await response.json()).data.status,expected);}assert.equal(f.calls.length,3);});
});
test('HTTP 502 wrapping retains multi-account and human-verification payloads',async()=>{
  const f=fixture({login_cellphone:()=>{throw {status:502,body:{status:0,error_code:34175,data:{info_list:[{userid:123}]}},cookie:[]};},captcha_sent:()=>{throw {status:502,body:{status:0,error_code:20028,ssaCode:'challenge-event'},headers:{'SSA-CODE':'challenge-event'}};}});
  await connection(f,async(base)=>{
    let response=await fetch(base+'/login/cellphone?mobile=fixture&code=fixture',{headers});assert.equal(response.status,502);assert.equal((await response.json()).data.info_list[0].userid,123);
    response=await fetch(base+'/captcha/sent?mobile=fixture',{headers});assert.equal(response.status,502);assert.equal(response.headers.get('ssa-code'),'challenge-event');assert.equal((await response.json()).ssaCode,'challenge-event');
  });
});
test('upstream cookies are returned without treating them as an implicit account login',async()=>{
  const f=fixture({login_qr_check:()=>({status:200,body:{status:1,data:{status:4,token:'account-token',userid:123}},cookie:['token=account-token','userid=123']})});
  await connection(f,async(base)=>{const response=await fetch(base+'/login/qr/check?key=fixture',{headers});assert.ok(response.headers.get('set-cookie').includes('token=account-token'));assert.equal((await response.json()).data.userid,123);assert.equal(f.calls.length,1);});
});
test('ordinary upstream failures contain no internal exception text or credential URLs',async()=>{
  const f=fixture({search:()=>{throw new Error('https://secret.invalid?token=private');}});
  await connection(f,async(base)=>{const response=await fetch(base+'/search?keywords=fixture',{headers});assert.equal(response.status,502);const text=await response.text();assert.equal(text.includes('private'),false);assert.equal(text.includes('secret.invalid'),false);});
});
test('invalid local transport secret cannot start a service',async()=>{
  await assert.rejects(()=>start({...config,token:''},fixture().options));
});

test('cloud create parameters remain typed for the original module and local auth is required',async()=>{
  const f=fixture({playlist_add:params=>{assert.equal(params.type,0);assert.equal(params.is_pri,1);assert.equal(params.source,1);assert.equal(params.cookie.userid,'123');return {status:200,body:{status:1,data:{listid:88}},cookie:[]};}});
  await connection(f,async(base)=>{assert.equal((await fetch(base+'/playlist/add?name=fixture&type=0&is_pri=1&source=1')).status,403);assert.equal(f.calls.length,0);const response=await fetch(base+'/playlist/add?name=fixture&type=0&is_pri=1&source=1',{headers:{...headers,Authorization:'token=fixture;userid=123'}});assert.equal((await response.json()).data.listid,88);});
});
test('original cloud add/delete modules receive catalog payload and playlist file IDs',async()=>{
  const captured=[];
  const options={util:{calculateMid:()=> 'fixture-mid'},moduleLoader:name=>require('../../android-api/module/'+name+'.js'),createRequest:async value=>{captured.push(value);return {status:200,body:{status:1},cookie:[]};}};
  await connection({options},async(base)=>{
    const credential={...headers,Authorization:'token=fixture-account;userid=123'};
    let response=await fetch(base+'/playlist/tracks/add?listid=5&data='+encodeURIComponent('song|0123456789abcdef0123456789abcdef|7|77'),{headers:credential});assert.equal((await response.json()).status,1);assert.equal(captured[0].data.listid,'5');assert.equal(captured[0].data.data[0].mixsongid,77);assert.equal(captured[0].data.data[0].hash,'0123456789abcdef0123456789abcdef');
    response=await fetch(base+'/playlist/tracks/del?listid=5&fileids=9090',{headers:credential});assert.equal((await response.json()).status,1);assert.equal(captured[1].data.data[0].fileid,9090);assert.equal(captured[1].data.userid,'123');
  });
});
