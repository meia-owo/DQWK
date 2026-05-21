const fs = require('fs');
const glob = require('glob');

const validImageBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNkYAAAAAYAAjCB0C8AAAAASUVORK5CYII=";
const buffer = Buffer.from(validImageBase64, 'base64');

const files = glob.sync('android/app/src/main/res/mipmap-*/*.png');

for (const file of files) {
  fs.writeFileSync(file, buffer);
  console.log(`Replaced ${file}`);
}
