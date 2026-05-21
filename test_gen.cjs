const fs = require('fs');
const buffer = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAAXNSR0IArs4c6QAAAAtJREFUGFdjYAACAAAFAAEE9nLzAAAAAElFTkSuQmCC', 'base64');
fs.writeFileSync('assets/icon.png', buffer);
fs.writeFileSync('assets/splash.png', buffer);
