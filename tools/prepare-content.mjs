import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const root = process.cwd();
const text = await fs.readFile(path.join(root, 'llm info.txt'), 'utf8');
const base = text.match(/https?:\/\/\S+/)[0].replace(/\/$/, '');
const key = text.match(/(?:sk-)[A-Za-z0-9_-]+/)?.[0] || text.split(/\r?\n/).map(s=>s.trim()).find(s=>/^[A-Za-z0-9_-]{24,}$/.test(s));
if (!key) throw Error('Cannot parse API credential');
const out = path.join(root,'app/src/main/assets');
const prompt = `你是小学英语课程编辑。根据所提供的按顺序排列的课文图片，准确识别全部课文正文，不把练习指令、页码、词汇表当成正文，不遗漏后续图片中的正文。保持人物说话顺序。绝不编造看不清的内容，在 note 标记待校对处。每个完整句子独立一项。生成可供家长校对的课程，只输出 JSON，不用 markdown。schema: {"title":"英文课文标题","subtitle":"简短中文主题","note":"识别备注，无问题填空串","sentences":[{"en":"完整英文句子，不含人物前缀","zh":"适合小学生的准确中文翻译","speaker":"原文说话人或空串"}],"vocabulary":[{"en":"原文中的重点单词或短语或重点句子","zh":"中文释义","kind":"word 或 phrase 或 sentence","note":"简短语法/用法提示","phonetic":"单词国际音标，其他可为空"}],"questions":[{"prompt":"中文阅读理解问题","options":["中文选项A","中文选项B","中文选项C","中文选项D"],"answer":0,"explanation":"中文解释，引用课文依据"}]}. vocabulary含8-12个word、4-6个phrase和3个sentence，均来自原文。questions生成4个有唯一正确答案的理解题，answer是正确选项零基下标。正文必须忠实图片，不要缩写改写，不要增加正文。`;
async function call(body) {
  const response = await fetch(base+'/chat/completions',{method:'POST',headers:{'Content-Type':'application/json','api-key':key},body:JSON.stringify(body),signal:AbortSignal.timeout(240000)});
  if(!response.ok) throw Error(`API HTTP ${response.status}`);
  return response.json();
}
for (let n=1;n<=4;n++) {
  const output=path.join(out,'courses',`lesson${n}.json`);
  try { await fs.access(output); console.log(`lesson${n}: using saved content`); continue; } catch {}
  const folder=path.join(root,`课文${n}`);
  const names=(await fs.readdir(folder)).filter(x=>/\.(jpg|png|jpeg)$/i.test(x)).sort();
  const content=[{type:'text',text:prompt}];
  const sources=[];
  for(let i=0;i<names.length;i++) {
    const bytes=await fs.readFile(path.join(folder,names[i]));
    content.push({type:'image_url',image_url:{url:'data:image/jpeg;base64,'+bytes.toString('base64')}});
    const source=`courses/lesson${n}-page${i+1}.jpg`;
    await fs.copyFile(path.join(folder,names[i]),path.join(out,source)); sources.push(source);
  }
  const response=await call({model:'mimo-v2.6-flash',messages:[{role:'user',content}],temperature:0.15,max_tokens:10000,thinking:{type:'disabled'}});
  const raw=response.choices[0].message.content;
  const course=JSON.parse(raw.slice(raw.indexOf('{'),raw.lastIndexOf('}')+1));
  course.id=`lesson${n}`;course.published=true;course.sources=sources;course.createdAt=Date.now();
  if(!course.sentences?.length || !course.vocabulary?.length || !course.questions?.length) throw Error('Incomplete content');
  await fs.writeFile(output,JSON.stringify(course,null,2));
  console.log(`lesson${n}: ${course.title}, ${course.sentences.length} sentences, ${course.vocabulary.length} key items`);
}
if (!process.argv.includes('--audio')) process.exit(0);
const sentences=new Set();
for(let n=1;n<=4;n++) {const course=JSON.parse(await fs.readFile(path.join(out,'courses',`lesson${n}.json`),'utf8'));for(const x of [...course.sentences,...course.vocabulary]) sentences.add(x.en);}
let done=0;const items=[...sentences];
async function worker() {
  while(items.length) {
    const en=items.shift();const hash=crypto.createHash('sha256').update(en).digest('hex');const file=path.join(out,'audio',hash+'.mp3');
    try {await fs.access(file);done++;continue;}catch{}
    let error;
    for(let attempt=0;attempt<3;attempt++) {
      try {const result=await call({model:'mimo-v2.5-tts',messages:[{role:'user',content:'Read the exact English text in clear, warm English for a child learning English. Speak slowly and naturally. Do not add any words.'},{role:'assistant',content:en}],audio:{format:'mp3',voice:'Mia'}});const bytes=Buffer.from(result.choices[0].message.audio.data,'base64');if(bytes.length<200)throw Error('Empty audio');await fs.writeFile(file,bytes);error=null;break;} catch(e){error=e;await new Promise(r=>setTimeout(r,1200*(attempt+1)));}
    }
    if(error) console.log('Audio failed: '+en+' '+error.message);else {done++;if(done%10===0)console.log(`Audio ready ${done}/${sentences.size}`);}
  }
}
await Promise.all([worker(),worker(),worker()]);
console.log(`Audio complete ${done}/${sentences.size}`);
