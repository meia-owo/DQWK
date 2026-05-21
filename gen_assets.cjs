const fs = require('fs');
const path = require('path');
const { createCanvas } = require('canvas');

const dir = path.join(__dirname, 'assets');
if (!fs.existsSync(dir)) {
  fs.mkdirSync(dir, { recursive: true });
}

// 512x512 icon
const iconCanvas = createCanvas(512, 512);
const iconCtx = iconCanvas.getContext('2d');
iconCtx.fillStyle = '#1e3a8a';
iconCtx.fillRect(0, 0, 512, 512);
fs.writeFileSync(path.join(dir, 'icon.png'), iconCanvas.toBuffer('image/png'));

// 2732x2732 splash
const splashCanvas = createCanvas(2732, 2732);
const splashCtx = splashCanvas.getContext('2d');
splashCtx.fillStyle = '#1e3a8a';
splashCtx.fillRect(0, 0, 2732, 2732);
fs.writeFileSync(path.join(dir, 'splash.png'), splashCanvas.toBuffer('image/png'));

console.log('Created assets/icon.png and assets/splash.png');
