const fs = require('fs');

let text = fs.readFileSync('./android/app/src/main/java/com/Kproject/app/MainActivity.kt', 'utf8');

// Set MainActivity fields and methods to public
text = text.replace(/private val REQUEST_/g, 'val REQUEST_');
text = text.replace(/private var pendingCall/g, 'var pendingCall');
text = text.replace(/private var current/g, 'var current');
text = text.replace(/private var originalBrightness/g, 'var originalBrightness');
text = text.replace(/private var batteryReceiver/g, 'var batteryReceiver');
text = text.replace(/private fun /g, 'fun ');

// Convert inner class to normal class
text = text.replace('inner class OverlayPlugin : Plugin() {', 'class OverlayPlugin : Plugin() {\n        private val mainActivity: MainActivity\n            get() = activity as MainActivity');

// Replace this@MainActivity
text = text.replace(/this@MainActivity/g, 'mainActivity');

// Prefix fields in OverlayPlugin
const pluginStart = text.indexOf('class OverlayPlugin : Plugin() {');
if (pluginStart !== -1) {
    let before = text.substring(0, pluginStart);
    let pluginText = text.substring(pluginStart);

    pluginText = pluginText.replace(/(?<!mainActivity\.)\bisAccessibilityServiceEnabled\(/g, 'mainActivity.isAccessibilityServiceEnabled(');
    pluginText = pluginText.replace(/(?<!mainActivity\.)\bshowAccessibilityDialog\(/g, 'mainActivity.showAccessibilityDialog(');
    pluginText = pluginText.replace(/(?<!mainActivity\.)\brequestBatteryOptimizationExemption\(/g, 'mainActivity.requestBatteryOptimizationExemption(');
    pluginText = pluginText.replace(/(?<!mainActivity\.)\bgetNetworkStatus\(/g, 'mainActivity.getNetworkStatus(');
    pluginText = pluginText.replace(/(?<!mainActivity\.)\bstartMediaProjection\(/g, 'mainActivity.startMediaProjection(');
    pluginText = pluginText.replace(/(?<!mainActivity\.)\bsaveSettings\(/g, 'mainActivity.saveSettings(');
    pluginText = pluginText.replace(/(?<!mainActivity\.)\bpendingCall\b(\s*)=/g, 'mainActivity.pendingCall$1=');
    pluginText = pluginText.replace(/(?<!mainActivity\.)\bpendingCall\?/g, 'mainActivity.pendingCall?');

    const fields = ['currentTargetKeywords', 'currentIsAutoBattleEnabled', 'currentTapOffsetX', 'currentTapOffsetY', 'currentScanInterval', 'currentEnableResultDetection', 'currentAutoBrightness', 'packageName', 'windowManager'];
    for (let f of fields) {
        let regex = new RegExp(`(?<!mainActivity\\.)\\b${f}\\b`, 'g');
        pluginText = pluginText.replace(regex, `mainActivity.${f}`);
    }

    pluginText = pluginText.replace(/(?<!\.)\bgetSystemService\(/g, 'mainActivity.getSystemService(');
    
    // REQUEST variables
    pluginText = pluginText.replace(/(?<!mainActivity\.)\bREQUEST_CAMERA_PERMISSION\b/g, 'mainActivity.REQUEST_CAMERA_PERMISSION');
    pluginText = pluginText.replace(/(?<!mainActivity\.)\bREQUEST_OVERLAY_PERMISSION\b/g, 'mainActivity.REQUEST_OVERLAY_PERMISSION');

    text = before + pluginText;
}

fs.writeFileSync('./android/app/src/main/java/com/Kproject/app/MainActivity.kt', text);
console.log('done');
