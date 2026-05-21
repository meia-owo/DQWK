const fs = require('fs');
const path = require('path');

const validImageBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
const buffer = Buffer.from(validImageBase64, 'base64');

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

replacePngs('android/app/src/main/res');
