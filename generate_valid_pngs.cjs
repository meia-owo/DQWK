const fs = require('fs');
const path = require('path');
const { createCanvas } = require('canvas');

const canvas = createCanvas(512, 512);
const ctx = canvas.getContext('2d');
ctx.fillStyle = '#000000';
ctx.fillRect(0, 0, 512, 512);
const buffer = canvas.toBuffer('image/png');

function replacePngs(dir) {
  if (fs.existsSync(dir)) {
    const list = fs.readdirSync(dir);
    for (const item of list) {
      const fullPath = path.join(dir, item);
      const stat = fs.statSync(fullPath);
      if (stat.isDirectory()) {
        replacePngs(fullPath);
      } else if (item.endsWith('.png')) {
        fs.writeFileSync(fullPath, buffer);
        console.log('Replaced', fullPath);
      }
    }
  }
}

replacePngs('./android/app/src/main/res');
