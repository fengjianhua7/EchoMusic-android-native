'use strict';

// Private on-device transport. No HTML, shared proxy, response cache, or public listener is exposed.
const http = require('node:http');
const crypto = require('node:crypto');
const ROUTES = Object.freeze({
  '/search': 'search',
  '/audio/match': 'audio_match',
  '/search/hot': 'search_hot',
  '/images': 'images',
  '/playlist/track/all': 'playlist_track_all',
  '/playlist/track/all/new': 'playlist_track_all_new',
  '/playlist/tracks/add': 'playlist_tracks_add',
  '/playlist/tracks/del': 'playlist_tracks_del',
  '/playlist/add': 'playlist_add',
  '/user/verify': 'user_verify',
  '/song/url/auth/merge': 'song_url_auth_merge',
  '/audio/accompany/matching': 'audio_accompany_matching',
  '/search/lyric': 'search_lyric',
  '/lyric': 'lyric',
  '/captcha/sent': 'captcha_sent',
  '/login/cellphone': 'login_cellphone',
  '/login/qr/key': 'login_qr_key',
  '/login/qr/create': 'login_qr_create',
  '/login/qr/check': 'login_qr_check',
  '/login/qq/qr/create': 'login_qq_qr_create',
  '/login/qq/qr/check': 'login_qq_qr_check',
  '/login/wx/create': 'login_wx_create',
  '/login/wx/check': 'login_wx_check',
  '/login/openplat': 'login_openplat',
  '/get/verify/info': 'get_verify_info',
  '/sidedt': 'sidedt',
  '/register/dev': 'register_dev',
  '/user/detail': 'user_detail',
  '/user/playlist': 'user_playlist',
});
function cookies(text) {
  const result = Object.create(null);
  for (const part of String(text || '').split(';')) {
    const index = part.indexOf('=');
    if (index > 0) result[part.slice(0, index).trim()] = part.slice(index + 1).trim();
  }
  return result;
}
function send(response, status, body, headers = {}) {
  if (response.destroyed || response.writableEnded) return;
  response.writeHead(status, {'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store',...headers});
  response.end(JSON.stringify(body));
}
async function start(config, testing = {}) {
  if (!config || !/^[a-f0-9]{64}$/.test(config.token)) throw new Error('Invalid local transport token');
  process.env.platform = 'lite';
  const path = require('node:path');
  const util = testing.util || require('./util/util');
  const defaults = Object.freeze({
    KUGOU_API_PLATFORM: 'lite',
    KUGOU_API_GUID: config.guid,
    KUGOU_API_MID: util.calculateMid(config.guid),
    KUGOU_API_DEV: config.dev,
    KUGOU_API_MAC: '02:00:00:00:00:00',
    uuid: config.uuid,
  });
  const secret = Buffer.from(config.token, 'utf8');
  const modules = new Map();
  const createRequest = testing.createRequest || require('./util/request').createRequest;
  const server = http.createServer(async (request, response) => {
    const provided = Buffer.from(String(request.headers['x-echo-token'] || ''), 'utf8');
    if (provided.length !== secret.length || !crypto.timingSafeEqual(provided, secret)) return send(response,403,{status:0,msg:'Local authentication required'});
    const audioMatch=request.method==='POST' && request.url.split('?')[0]==='/audio/match';
    if (request.method !== 'GET' && !audioMatch) return send(response,405,{status:0,msg:'Method not supported'});
    if (request.url.length > 32768) return send(response,414,{status:0,msg:'Request too large'});
    let url;
    try { url = new URL(request.url,'http://127.0.0.1'); } catch { return send(response,400,{status:0,msg:'Invalid request'}); }
    if (url.pathname === '/__health') return send(response,200,{status:1,runtime:process.versions.node,version:3});
    if(url.pathname==='/__qr'){const value=url.searchParams.get('text')||'';if(value.length>512)return send(response,400,{status:0});try{return send(response,200,{status:1,image:await require('qrcode').toDataURL(value)});}catch{return send(response,400,{status:0});}}
    const name = ROUTES[url.pathname];
    if (!name) return send(response,404,{status:0,msg:'Unknown route'});
    const params = Object.fromEntries(url.searchParams);
    if (name==='audio_match') {
      const length=Number(request.headers['content-length']);
      if (!audioMatch || request.headers['content-type']!=='application/octet-stream' ||
          !Number.isInteger(length) || length<16000 || length>192000 || length%2) {
        return send(response,400,{status:0,msg:'无效的录音数据'});
      }
      const chunks=[];
      let size=0;
      try{
        for await (const chunk of request){
          size+=chunk.length;
          if(size>length)return send(response,413,{status:0,msg:'录音数据过大'});
          chunks.push(chunk);
        }
      }catch{return;}
      if(size!==length)return send(response,400,{status:0,msg:'录音不完整'});
      params.data=Buffer.concat(chunks,size);
    }
    // QQ supplies its own polling cookie as an explicit query field. Device and account headers win.
    const cookie = Object.assign(Object.create(null),defaults,cookies(params.cookie),cookies(request.headers.cookie),cookies(request.headers.authorization));
    params.cookie = cookie;
    if(name === 'playlist_add'){params.type=Number(params.type||0);params.is_pri=Number(params.is_pri||0);params.source=Number(params.source||1);}
    const deviceHeaders = Object.entries(defaults).map(([key,value])=>`${key}=${value}; Path=/; HttpOnly; SameSite=Strict`);
    try {
      if (!modules.has(name)) modules.set(name,testing.moduleLoader ? testing.moduleLoader(name) : require(path.join(__dirname,'module',name+'.js')));
      // Normalize only the documented lyric-service success shape before the original
      // download module runs its decode step. The shared requester rejects code 200.
      const lyricRequest=async (...args)=>{
        let result;
        try{result=await createRequest(...args);}catch(error){
          const body=error&&error.body;
          if(!body||Number(body.error_code)!==200||Number(body.status)===0||!(Array.isArray(body.candidates)||typeof body.content==='string'&&body.content.length))throw error;
          result=error;
        }
        const body=result&&result.body;
        if(body&&Number(body.error_code)===200&&Number(body.status)!==0&&(Array.isArray(body.candidates)||typeof body.content==='string'&&body.content.length))return {...result,status:200,body:{...body,error_code:0}};
        return result;
      };
      const result = await modules.get(name)(params,(name==='lyric'||name==='search_lyric')?lyricRequest:createRequest);
      const headers = Object.create(null);
      // Only the upstream challenge header is needed; arbitrary upstream response headers are not forwarded.
      for (const [key,value] of Object.entries(result.headers || {})) if (key.toLowerCase() === 'ssa-code') headers['ssa-code'] = value;
      const returnedCookies = Array.isArray(result.cookie) ? result.cookie.filter(value=>typeof value==='string'&&!/[\r\n]/.test(value)) : [];
      headers['Set-Cookie'] = deviceHeaders.concat(returnedCookies.map(value=>value+'; Path=/; HttpOnly; SameSite=Strict'));
      const status = Number(result.status || result.code || 200);
      send(response,status >= 200 && status <= 599 ? status : 502,result.body || {status:0,msg:'Empty upstream response'},headers);
    } catch (error) {
      const body = error && error.body && typeof error.body === 'object' ? error.body : {status:0,msg:'网络请求失败，请检查网络后重试'};
      const headers = {'Set-Cookie':deviceHeaders};
      for (const [key,value] of Object.entries(error && error.headers || {})) if (key.toLowerCase() === 'ssa-code') headers['ssa-code'] = value;
      send(response,502,body,headers);
    }
  });
  server.requestTimeout=35000;
  server.headersTimeout=10000;
  server.keepAliveTimeout=1000;
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',resolve);});
  return {port:server.address().port,server};
}
module.exports={start,ROUTES,cookies};
