const fs = require('fs');
const path = require('path');

// A valid 1x1 transparent PNG structure
const validPngBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAAXNSR0IArs4c6QAAAAtJREFUGFdjYAACAAAFAAEE9nLzAAAAAElFTkSuQmCC";
const buffer = Buffer.from(validPngBase64, 'base64');

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
