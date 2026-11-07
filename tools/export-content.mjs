import fs from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';
const source='app/src/androidTest/assets/fixtures';
const target='deliverables/课程素材';
await fs.mkdir(target,{recursive:true});
await fs.cp(source+'/courses',target+'/courses',{recursive:true});
await fs.mkdir(target+'/audio',{recursive:true});
const index=[];const used=new Set();
for(let n=1;n<=4;n++) {
 const c=JSON.parse(await fs.readFile(`${source}/courses/lesson${n}.json`,'utf8'));
 const sentenceItems=c.sentences.map(addAudio),vocabItems=c.vocabulary.map(addAudio);
 index.push({id:c.id,title:c.title,subtitle:c.subtitle,sentences:sentenceItems,vocabulary:vocabItems,questions:c.questions,sources:c.sources});
 const lines=[c.title,c.subtitle,'','【课文与逐句翻译】',...sentenceItems.flatMap((s,i)=>[`${i+1}. ${s.speaker?s.speaker+': ':''}${s.en}`,s.zh,`音频：${s.audio}`,'']),'【重点词汇、短语和句子】',...vocabItems.flatMap(v=>[`${({word:'单词',phrase:'短语',sentence:'重点句'})[v.kind]}：${v.en} ${v.phonetic||''}`,v.zh,v.note,`音频：${v.audio}`,''])];
 await fs.writeFile(`${target}/课文${n}.txt`,lines.join('\r\n'),'utf8');
}
function addAudio(item){const file=crypto.createHash('sha256').update(item.en).digest('hex')+'.mp3';used.add(file);return {...item,audio:'audio/'+file};}
for(const file of used)await fs.copyFile(path.join(source,'audio',file),path.join(target,'audio',file));
await fs.writeFile(target+'/素材索引.json',JSON.stringify(index,null,2));
console.log(`Exported 4 courses and ${used.size} audio files. No API credentials included.`);
