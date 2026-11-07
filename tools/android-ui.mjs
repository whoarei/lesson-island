// Small ADB accessibility helper for the dedicated test emulator. Does not read API credentials.
import {execFileSync} from 'node:child_process';
import fs from 'node:fs/promises';
const device=process.argv[2];if(!device)throw Error('Pass an explicit emulator serial.');
const adb=process.env.ANDROID_ADB||'E:/Android/sdk/platform-tools/adb.exe';
const shell=(...args)=>execFileSync(adb,['-s',device,'shell',...args],{encoding:'utf8',timeout:25000});
const wait=(ms=300)=>new Promise(r=>setTimeout(r,ms));
function decode(v){return v.replace(/&quot;/g,'"').replace(/&apos;/g,"'").replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>');}
async function nodes(){await wait();shell('uiautomator','dump','/sdcard/lessonisland-ui.xml');const xml=shell('cat','/sdcard/lessonisland-ui.xml');return [...xml.matchAll(/<node\s+([^>]+)>?/g)].map(m=>Object.fromEntries([...m[1].matchAll(/([\w-]+)="([^"]*)"/g)].map(a=>[a[1],decode(a[2])])));}
function tapNode(node){const n=node.bounds.match(/\d+/g).map(Number);shell('input','tap',String(Math.round((n[0]+n[2])/2)),String(Math.round((n[1]+n[3])/2)));}
async function tap(text,{contains=false}={}){const ns=await nodes();const match=ns.find(n=>n.enabled==='true'&&(contains?n.text.includes(text)||n['content-desc'].includes(text):n.text===text||n['content-desc']===text));if(!match)throw Error('Cannot find control: '+text);tapNode(match);await wait();}
async function inspect(){console.log((await nodes()).filter(n=>n.text||n['content-desc']||n.class==='android.widget.EditText').map(n=>({text:n.password==='true'?'[masked]':n.text,desc:n['content-desc'],hint:n.hint,bounds:n.bounds})));}
async function screenshot(name){await wait(450);shell('screencap','-p','/sdcard/lessonisland-release.png');execFileSync(adb,['-s',device,'pull','/sdcard/lessonisland-release.png',name],{stdio:'ignore'});}
const action=process.argv[3]||'inspect';
if(action==='inspect')await inspect();
else if(action==='tap'){await tap(process.argv[4],{contains:process.argv.includes('--contains')});await inspect();}
else if(action==='swipe'){shell('input','swipe','530','1470','530','550','400');await inspect();}
else if(action==='screenshot')await screenshot(process.argv[4]);
else if(action==='setup-pin'){
 await tap('切换模式');const inputs=(await nodes()).filter(n=>n.class==='android.widget.EditText');if(inputs.length!==2)throw Error('Expected first-run PIN setup.');
 for(const input of inputs){tapNode(input);shell('input','text','2468');}
 await tap('设置并进入');await inspect();
}else if(action==='enter-pin'){
 await tap('切换模式');const inputs=(await nodes()).filter(n=>n.class==='android.widget.EditText');if(inputs.length!==1)throw Error('Expected PIN gate.');tapNode(inputs[0]);shell('input','text','2468');await tap('进入');await inspect();
}else if(action==='text'){
 const inputs=(await nodes()).filter(n=>n.class==='android.widget.EditText');const index=Number(process.argv[4]||0);if(!inputs[index])throw Error('Input not found');tapNode(inputs[index]);shell('input','text',process.argv[5]);await inspect();
}else if(action==='tap-at'){shell('input','tap',process.argv[4],process.argv[5]);await inspect();}
else if(action==='back'){shell('input','keyevent','4');await inspect();}
else throw Error('Unknown action');
