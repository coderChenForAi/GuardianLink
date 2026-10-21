// 生成内置告警音 res/raw/alarm.wav：双频警笛（780/980Hz 交替），2.4s 循环，16bit 单声道 16kHz。
const fs = require('fs');
const path = require('path');

const sr = 16000;
const dur = 2.4;
const n = Math.floor(sr * dur);
const samples = Buffer.alloc(n * 2);

for (let i = 0; i < n; i++) {
  const t = i / sr;
  const f = Math.floor(t / 0.3) % 2 === 0 ? 780 : 980;
  const inTone = (t % 0.3) < 0.25;
  const env = Math.min(1, (t % 0.3) / 0.02) * Math.min(1, Math.max(0, (0.3 - (t % 0.3)) / 0.05));
  const v = inTone ? Math.sin(2 * Math.PI * f * t) * 0.85 * env : 0;
  samples.writeInt16LE(Math.round(v * 32767), i * 2);
}

const header = Buffer.alloc(44);
header.write('RIFF', 0);
header.writeUInt32LE(36 + samples.length, 4);
header.write('WAVE', 8);
header.write('fmt ', 12);
header.writeUInt32LE(16, 16);
header.writeUInt16LE(1, 20); // PCM
header.writeUInt16LE(1, 22); // mono
header.writeUInt32LE(sr, 24);
header.writeUInt32LE(sr * 2, 28);
header.writeUInt16LE(2, 32);
header.writeUInt16LE(16, 34);
header.write('data', 36);
header.writeUInt32LE(samples.length, 40);

const out = path.join(__dirname, '..', 'app', 'src', 'main', 'res', 'raw', 'alarm.wav');
fs.writeFileSync(out, Buffer.concat([header, samples]));
console.log('written:', out, (44 + samples.length) + ' bytes');
