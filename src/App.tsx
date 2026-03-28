/**
 * @license
 * SPDX-License-Identifier: Apache-2.0
 */

import React, { useState, useEffect, useRef } from 'react';
import { motion, Reorder } from 'motion/react';
import { 
  Activity, Battery, BatteryMedium, BatteryWarning,
  Thermometer, ThermometerSun, Settings, Play, Square, 
  Leaf, Scan, GripHorizontal, GripVertical, Download, ScanText,
  ChevronDown, ChevronRight, HelpCircle, Monitor, AlertTriangle,
  LocateFixed, X
} from 'lucide-react';
import { createWorker, Worker } from 'tesseract.js';
import { Capacitor, registerPlugin } from '@capacitor/core';

interface OverlayPlugin {
  startOverlay(): Promise<void>;
  updateSettings(options: {
    targetKeyword: string;
    isAutoBattleEnabled: boolean;
    tapOffsetX: number;
    tapOffsetY: number;
    scanInterval: number;
    enableResultDetection: boolean;
  }): Promise<void>;
}

const OverlayPlugin = registerPlugin<OverlayPlugin>('OverlayPlugin');

// Custom hook for intervals
function useInterval(callback: () => void, delay: number | null) {
  const savedCallback = useRef(callback);
  useEffect(() => {
    savedCallback.current = callback;
  }, [callback]);
  useEffect(() => {
    if (delay !== null) {
      const id = setInterval(() => savedCallback.current(), delay);
      return () => clearInterval(id);
    }
  }, [delay]);
}

type PartyMember = {
  id: number;
  name: string;
  job: string;
  level: number;
  currentExp: number;
  nextExp: number;
};

const initialParty: PartyMember[] = [
  { id: 1, name: 'アルくん', job: '天地雷鳴士', level: 87, currentExp: 12000, nextExp: 162500 },
  { id: 2, name: 'ドラちゃん', job: 'ニンジャ', level: 89, currentExp: 15000, nextExp: 183500 },
  { id: 3, name: 'ななぽん', job: '大魔道士', level: 88, currentExp: 18000, nextExp: 242500 },
  { id: 4, name: 'こっこやで', job: '魔剣士', level: 86, currentExp: 10000, nextExp: 129000 },
];

export default function App() {
  const constraintsRef = useRef(null);
  
  // System States
  const [isAutoBattleEnabled, setIsAutoBattleEnabled] = useState(true);
  const [isAutoRun, setIsAutoRun] = useState(false);
  const [isEcoMode, setIsEcoMode] = useState(false);
  const [scanInterval, setScanInterval] = useState(3);
  const [battery, setBattery] = useState(85);
  const [tempCounter, setTempCounter] = useState(0);
  const [temperature, setTemperature] = useState<'Normal' | 'Warm' | 'Hot'>('Normal');
  
  // New Control Specs
  const [ocrRetryCount, setOcrRetryCount] = useState(3);
  const [ocrWaitTime, setOcrWaitTime] = useState(1.5);
  const [enableDictCorrection, setEnableDictCorrection] = useState(true);
  const [enablePotFilter, setEnablePotFilter] = useState(true);
  const [pauseScanOnBattle, setPauseScanOnBattle] = useState(true);
  
  // Battle Target Filters
  const [targetMetal, setTargetMetal] = useState(true);
  const [targetKakutei, setTargetKakutei] = useState(true);
  const [targetKoukaku, setTargetKoukaku] = useState(true);
  const [targetNormalEnemy, setTargetNormalEnemy] = useState(true);
  const [targetStrongEnemy, setTargetStrongEnemy] = useState(true);
  const [targetEventPop, setTargetEventPop] = useState(true);
  const [targetPot, setTargetPot] = useState(true);
  const [targetHokora, setTargetHokora] = useState(true);
  const [targetOther, setTargetOther] = useState(false);

  // Tap Priorities
  const defaultPriorities = [
    { id: 'metal', label: 'メタルモンスター' },
    { id: 'kakutei', label: 'かくてい！　モンスター' },
    { id: 'koukaku', label: 'こうかく！　モンスター' },
    { id: 'normal', label: '！ 通常の敵' },
    { id: 'strong', label: 'どこでも 強敵' },
    { id: 'event', label: '··· イベントポップ' },
    { id: 'pot', label: '壺' },
    { id: 'hokora', label: 'ほこら' },
    { id: 'other', label: '※ それ以外' }
  ];

  const [tapPriorities1, setTapPriorities1] = useState(defaultPriorities);
  const [tapPriorities2, setTapPriorities2] = useState(defaultPriorities);
  const [activePriorityIndex, setActivePriorityIndex] = useState<1 | 2>(1);

  const tapPriorities = activePriorityIndex === 1 ? tapPriorities1 : tapPriorities2;
  const setTapPriorities = activePriorityIndex === 1 ? setTapPriorities1 : setTapPriorities2;

  // Additional Control Specs from images
  const [enableAnchorSearch, setEnableAnchorSearch] = useState(true);
  const [wideScanArea, setWideScanArea] = useState(75); // 60-90%
  const [enableRegexFilter, setEnableRegexFilter] = useState(true);
  const [enablePartyScan, setEnablePartyScan] = useState(true);
  const [enableResultDetection, setEnableResultDetection] = useState(true);

  // Statistics
  const [totalKills, setTotalKills] = useState(0);
  const [totalExp, setTotalExp] = useState(0);
  const [averageExp, setAverageExp] = useState(0);
  const [startTime] = useState(Date.now());
  
  // Party State
  const [party, setParty] = useState<PartyMember[]>(initialParty);
  
  // UI States
  const [appStatus, setAppStatus] = useState<'WAIT' | 'WALK_MODE'>('WAIT');
  const [calibrationState, setCalibrationState] = useState<'IDLE' | 'UNLOCK_BTN' | 'CHAR_CENTER' | 'CIRCLE_EDGE' | 'OCR_SEARCHING' | 'BERSERKER_ICON' | 'BERSERKER_ITEM'>('IDLE');
  const [unlockBtnPos, setUnlockBtnPos] = useState(() => {
    const saved = localStorage.getItem('unlockBtnPos');
    return saved ? JSON.parse(saved) : { x: 0.5, y: 0.92 };
  });
  const [unlockBtnColor, setUnlockBtnColor] = useState(() => {
    const saved = localStorage.getItem('unlockBtnColor');
    return saved ? JSON.parse(saved) : { r: 235, g: 215, b: 185 };
  });
  const [charCenterPos, setCharCenterPos] = useState(() => {
    const saved = localStorage.getItem('charCenterPos');
    return saved ? JSON.parse(saved) : { x: 0.5, y: 0.6 };
  });
  const [circleRadius, setCircleRadius] = useState(() => {
    const saved = localStorage.getItem('circleRadius');
    return saved ? parseFloat(saved) : 0.25;
  });
  const [pullMargin, setPullMargin] = useState(() => {
    const saved = localStorage.getItem('pullMargin');
    return saved ? parseFloat(saved) : 1.2;
  });

  // Berserker Mode States
  const [isBerserkerMode, setIsBerserkerMode] = useState(() => localStorage.getItem('isBerserkerMode') === 'true');
  const [berserkerIconPos, setBerserkerIconPos] = useState(() => {
    const saved = localStorage.getItem('berserkerIconPos');
    return saved ? JSON.parse(saved) : { x: 0.1, y: 0.8 };
  });
  const [berserkerItemPos, setBerserkerItemPos] = useState(() => {
    const saved = localStorage.getItem('berserkerItemPos');
    return saved ? JSON.parse(saved) : { x: 0.3, y: 0.5 };
  });
  const [lastBerserkerTime, setLastBerserkerTime] = useState(() => {
    const saved = localStorage.getItem('lastBerserkerTime');
    return saved ? parseInt(saved, 10) : 0;
  });
  const isUsingItemRef = useRef(false);
  const [targetTapOffset, setTargetTapOffset] = useState<number>(() => {
    const saved = localStorage.getItem('targetTapOffset');
    return saved ? Number(saved) : -10; // デフォルトで少し上をタップ
  });

  const [taps, setTaps] = useState<{x: number, y: number, id: number}[]>([]);

  const [scanningMemberId, setScanningMemberId] = useState<number | null>(null);
  const isScanning = scanningMemberId !== null;
  const [showSettings, setShowSettings] = useState(false);
  const [showResetConfirm, setShowResetConfirm] = useState(false);
  const [showPrioritySettings, setShowPrioritySettings] = useState(false);
  const [showOCRWindow, setShowOCRWindow] = useState(false);
  const [isOcrSettingsExpanded, setIsOcrSettingsExpanded] = useState(false);
  const [uiScale, setUiScale] = useState(0.85);
  const [maxUiScale, setMaxUiScale] = useState(1.0);
  const [isDragConstrained, setIsDragConstrained] = useState(true);
  const [displayMode, setDisplayMode] = useState<'all' | 'stats' | 'party'>('all');
  const [time, setTime] = useState(new Date().toLocaleTimeString('en-US', { hour12: false }));

  // Overlay & Capture States
  const [stream, setStream] = useState<MediaStream | null>(null);
  const [showCaptureWarning, setShowCaptureWarning] = useState(false);
  const videoRef = useRef<HTMLVideoElement>(null);

  // OCR States
  const [ocrStatus, setOcrStatus] = useState<'OFF' | '初期化中' | '待機中' | '解析中' | 'クールダウン' | 'エラー'>('OFF');
  const workerRef = useRef<Worker | null>(null);
  const isProcessingRef = useRef(false);
  const lastProcessedTimeRef = useRef(0);
  const ocrTimeoutRef = useRef<NodeJS.Timeout | null>(null);

  // Update Native Overlay Settings
  useEffect(() => {
    if (Capacitor.isNativePlatform()) {
      OverlayPlugin.updateSettings({
        targetKeyword: tapPriorities[0].label,
        isAutoBattleEnabled: isAutoBattleEnabled,
        tapOffsetX: 0,
        tapOffsetY: targetTapOffset,
        scanInterval: scanInterval,
        enableResultDetection: enableResultDetection,
      }).catch(e => console.error('Failed to update overlay settings', e));
    }
  }, [isAutoBattleEnabled, targetTapOffset, tapPriorities, scanInterval, enableResultDetection]);

  // Initialize Tesseract Worker
  useEffect(() => {
    let isMounted = true;
    const initWorker = async () => {
      if (!enableResultDetection) {
        setOcrStatus('OFF');
        return;
      }
      setOcrStatus('初期化中');
      try {
        // 既存のワーカーがあれば終了させる
        if (workerRef.current) {
          await workerRef.current.terminate();
          workerRef.current = null;
        }
        
        const worker = await createWorker('jpn', 1, {
          logger: m => console.log(m), // 進行状況をログに出力
        });
        
        if (isMounted) {
          workerRef.current = worker;
          setOcrStatus('待機中');
        } else {
          await worker.terminate();
        }
      } catch (e) {
        console.error("OCR Init Error", e);
        if (isMounted) setOcrStatus('エラー');
      }
    };

    initWorker();

    return () => {
      isMounted = false;
      if (workerRef.current) {
        workerRef.current.terminate();
        workerRef.current = null;
      }
      if (ocrTimeoutRef.current) clearTimeout(ocrTimeoutRef.current);
    };
  }, [enableResultDetection]);

  useEffect(() => {
    if (videoRef.current && stream) {
      videoRef.current.srcObject = stream;
    }
  }, [stream]);

  const startCapture = async () => {
    try {
      const mediaStream = await navigator.mediaDevices.getDisplayMedia({
        video: {
          displaySurface: 'window'
        }
      });
      setStream(mediaStream);
      setShowCaptureWarning(false);
      
      mediaStream.getVideoTracks()[0].onended = () => {
        setStream(null);
      };
    } catch (err) {
      console.error("Error: " + err);
      setShowCaptureWarning(false);
    }
  };

  const stopCapture = () => {
    if (stream) {
      stream.getTracks().forEach(track => track.stop());
      setStream(null);
    }
  };

  const captureAndCrop = (video: HTMLVideoElement, rect: {x: number, y: number, w: number, h: number}) => {
    const canvas = document.createElement('canvas');
    canvas.width = rect.w;
    canvas.height = rect.h;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    ctx.drawImage(video, rect.x, rect.y, rect.w, rect.h, 0, 0, rect.w, rect.h);
    return canvas;
  };

  // 超軽量なピクセルカラー取得ユーティリティ
  const checkPixelColor = (video: HTMLVideoElement, xPct: number, yPct: number) => {
    const canvas = document.createElement('canvas');
    canvas.width = 1;
    canvas.height = 1;
    const ctx = canvas.getContext('2d');
    if (!ctx) return null;
    
    const x = Math.floor(video.videoWidth * xPct);
    const y = Math.floor(video.videoHeight * yPct);
    
    ctx.drawImage(video, x, y, 1, 1, 0, 0, 1, 1);
    const data = ctx.getImageData(0, 0, 1, 1).data;
    return { r: data[0], g: data[1], b: data[2] };
  };

  // 色判定のユーティリティ関数
  const isColorMatch = (color: {r: number, g: number, b: number} | null, target: {r: number, g: number, b: number}, tolerance = 40) => {
    if (!color) return false;
    const dist = Math.sqrt(
      Math.pow(color.r - target.r, 2) +
      Math.pow(color.g - target.g, 2) +
      Math.pow(color.b - target.b, 2)
    );
    return dist <= tolerance;
  };

  const effectiveScanInterval = isEcoMode ? Math.max(5, scanInterval) : scanInterval;

  // Clock
  useInterval(() => {
    setTime(new Date().toLocaleTimeString('en-US', { hour12: false }));
  }, 1000);

  // 超軽量な状態監視ループ（毎秒実行、負荷ほぼゼロ）
  useInterval(() => {
    if (!isAutoRun || !stream || !videoRef.current) {
      if (appStatus !== 'WAIT') setAppStatus('WAIT');
      return;
    }
    
    const video = videoRef.current;
    if (video.videoWidth === 0) return;

    // 「WALKモード中.」の文字（水色）の位置と色（仮）
    const walkTextPos = { x: 0.5, y: 0.85 };
    const walkTextColor = { r: 0, g: 220, b: 255 }; // シアン系

    // 「解除する」ボタン（茶色/ベージュ）の位置と色（キャリブレーション値）
    const color1 = checkPixelColor(video, walkTextPos.x, walkTextPos.y);
    const color2 = checkPixelColor(video, unlockBtnPos.x, unlockBtnPos.y);

    const isWalkTextMatch = isColorMatch(color1, walkTextColor, 50);
    const isUnlockBtnMatch = isColorMatch(color2, unlockBtnColor, 50);

    // どちらかの色が検知できればWALKモード、両方外れればWAIT状態（手動解除など）
    if (isWalkTextMatch || isUnlockBtnMatch) {
      if (appStatus !== 'WALK_MODE') setAppStatus('WALK_MODE');
    } else {
      if (appStatus !== 'WAIT') setAppStatus('WAIT');
    }
  }, 1000);

  // Battery & Temperature Simulation
  useInterval(() => {
    setBattery(prev => Math.max(0, prev - (isAutoRun ? (isEcoMode ? 0.02 : 0.08) : 0.01)));
    
    if (isAutoRun && !isEcoMode) {
      setTempCounter(prev => Math.min(100, prev + 0.8));
    } else {
      setTempCounter(prev => Math.max(0, prev - 0.5));
    }
  }, 1000);

  useEffect(() => {
    if (tempCounter > 80) setTemperature('Hot');
    else if (tempCounter > 40) setTemperature('Warm');
    else setTemperature('Normal');
  }, [tempCounter]);

  // Auto Eco Mode Trigger
  useEffect(() => {
    if ((battery < 20 || temperature === 'Hot') && !isEcoMode) {
      setIsEcoMode(true);
    }
  }, [battery, temperature, isEcoMode]);

  // Visual Tap Simulator
  const simulateTap = (xPct: number, yPct: number) => {
    const id = Date.now() + Math.random();
    setTaps(prev => [...prev, { x: xPct, y: yPct, id }]);
    setTimeout(() => {
      setTaps(prev => prev.filter(t => t.id !== id));
    }, 500);
  };

  // Berserker Sequence
  const executeBerserkerSequence = async () => {
    isUsingItemRef.current = true;
    
    // 1. Tap Main Screen Icon
    simulateTap(berserkerIconPos.x, berserkerIconPos.y);
    
    // 2. Wait for item menu to open
    await new Promise(resolve => setTimeout(resolve, 1500));
    
    // 3. Tap the item itself
    simulateTap(berserkerItemPos.x, berserkerItemPos.y);
    
    // 4. Update timer
    const now = Date.now();
    setLastBerserkerTime(now);
    localStorage.setItem('lastBerserkerTime', now.toString());
    
    isUsingItemRef.current = false;
  };

  // Berserker Mode (Aggressive Aromatherapy) Loop
  useInterval(() => {
    if (!isAutoRun || appStatus !== 'WALK_MODE' || !isBerserkerMode || isUsingItemRef.current) return;
    
    const now = Date.now();
    // 5 minutes = 300,000 ms
    if (now - lastBerserkerTime > 300000) {
      executeBerserkerSequence();
    }
  }, 1000);

  // Dynamic Max UI Scale
  useEffect(() => {
    const observer = new ResizeObserver((entries) => {
      for (let entry of entries) {
        const containerWidth = entry.contentRect.width;
        // Base width of HUD is 340px
        const maxScale = containerWidth / 340;
        setMaxUiScale(maxScale);
        setUiScale(prev => Math.min(prev, maxScale));
      }
    });
    
    if (constraintsRef.current) {
      observer.observe(constraintsRef.current);
    }
    
    return () => observer.disconnect();
  }, []);

  // Auto Run / OCR Scan Loop
  useInterval(async () => {
    // WAIT状態ならOCR処理を完全にスキップして負荷をゼロにする
    if (appStatus === 'WAIT' || !isAutoRun || !enableResultDetection || !stream || !videoRef.current || !workerRef.current || isProcessingRef.current) {
      return;
    }

    // Cooldown check (10 seconds after a successful read to avoid double counting)
    if (Date.now() - lastProcessedTimeRef.current < 10000) {
      setOcrStatus('クールダウン');
      return;
    }

    const video = videoRef.current;
    if (video.videoWidth === 0 || video.videoHeight === 0) return;

    isProcessingRef.current = true;
    setOcrStatus('解析中');

    // タイムアウト設定 (5秒以上かかったら強制終了)
    if (ocrTimeoutRef.current) clearTimeout(ocrTimeoutRef.current);
    ocrTimeoutRef.current = setTimeout(() => {
      if (isProcessingRef.current) {
        console.warn("OCR Timeout reached. Resetting...");
        isProcessingRef.current = false;
        setOcrStatus('待機中');
      }
    }, 5000);

    try {
      const vw = video.videoWidth;
      const vh = video.videoHeight;

      // 1. Field Target Detection (Exclamation Mark "！")
      if (appStatus === 'WALK_MODE') {
        // 画面中央付近をスキャン
        const fieldRect = { x: vw * 0.1, y: vh * 0.2, w: vw * 0.8, h: vh * 0.6 };
        const fieldCanvas = captureAndCrop(video, fieldRect);
        if (fieldCanvas) {
          const result = await workerRef.current.recognize(fieldCanvas);
          const fieldText = result.data.text;
          
          // 誤読対策: "！", "!", "i", "l", "1", "|" を許容
          const exclamationRegex = /[！!il1|]/;
          
          if (exclamationRegex.test(fieldText)) {
            if (targetNormalEnemy) {
              setOcrStatus('ターゲット捕捉');
              // キャラクター位置からオフセットを適用してタップ
              const tapX = charCenterPos.x;
              const tapY = charCenterPos.y + (targetTapOffset / 100);
              simulateTap(tapX, tapY);
              lastProcessedTimeRef.current = Date.now();
              
              // キャンバス解放のヒント
              fieldCanvas.width = 0;
              fieldCanvas.height = 0;
              return;
            }
          }
          fieldCanvas.width = 0;
          fieldCanvas.height = 0;
        }
      }

      // 2. Check Message Box (Average EXP)
      const msgRect = { x: vw * 0.1, y: vh * 0.5, w: vw * 0.8, h: vh * 0.2 };
      const msgCanvas = captureAndCrop(video, msgRect);
      
      if (msgCanvas) {
        const result = await workerRef.current.recognize(msgCanvas);
        const msgText = result.data.text;
        
        const expMatch = msgText.match(/(\d+)[^\d]*(?:経|験|値)/);

        if (expMatch) {
          const avgExp = parseInt(expMatch[1], 10);
          setAverageExp(avgExp);
          
          const charExps = [0, 0, 0, 0];
          const charWidth = vw * 0.25;
          const charY = vh * 0.65;
          const charH = vh * 0.1;

          if (!isEcoMode) {
            for (let i = 0; i < 4; i++) {
               const charRect = { x: charWidth * i, y: charY, w: charWidth, h: charH };
               const charCanvas = captureAndCrop(video, charRect);
               if (charCanvas) {
                  const charResult = await workerRef.current.recognize(charCanvas);
                  const charText = charResult.data.text;
                  const charExpMatch = charText.match(/(?:EXP|\+|P)[^\d]*(\d+)/i) || charText.match(/(\d+)/);
                  
                  if (charExpMatch) {
                     charExps[i] = parseInt(charExpMatch[1], 10);
                  } else {
                     charExps[i] = avgExp; 
                  }
                  charCanvas.width = 0;
                  charCanvas.height = 0;
               }
            }
          } else {
             for (let i = 0; i < 4; i++) charExps[i] = avgExp;
          }

          setTotalKills(prev => {
            const newKills = prev + 1;
            setParty(prevParty => {
              const newParty = prevParty.map((member, index) => {
                const expGained = charExps[index] || avgExp;
                let newExp = member.currentExp + expGained;
                let newLevel = member.level;
                let newNextExp = member.nextExp;

                while (newExp >= newNextExp) {
                  newExp -= newNextExp;
                  newLevel += 1;
                  newNextExp = Math.floor(newNextExp * 1.1);
                }

                return { ...member, level: newLevel, currentExp: newExp, nextExp: newNextExp };
              });

              try {
                const logsStr = localStorage.getItem('dq_macro_logs');
                let logs = logsStr ? JSON.parse(logsStr) : [];
                logs.push({
                  timestamp: new Date().toLocaleString('ja-JP'),
                  char1Name: newParty[0]?.name || '',
                  char1Exp: newParty[0]?.currentExp || 0,
                  char2Name: newParty[1]?.name || '',
                  char2Exp: newParty[1]?.currentExp || 0,
                  char3Name: newParty[2]?.name || '',
                  char3Exp: newParty[2]?.currentExp || 0,
                  char4Name: newParty[3]?.name || '',
                  char4Exp: newParty[3]?.currentExp || 0,
                  totalKills: newKills
                });
                if (logs.length > 1000) logs = logs.slice(logs.length - 1000);
                localStorage.setItem('dq_macro_logs', JSON.stringify(logs));
              } catch (e) {
                console.error('Failed to save log', e);
              }
              return newParty;
            });
            return newKills;
          });
          
          setTotalExp(prev => prev + charExps.reduce((a, b) => a + b, 0));
          lastProcessedTimeRef.current = Date.now();
        }
        msgCanvas.width = 0;
        msgCanvas.height = 0;
      }
    } catch (e) {
      console.error("OCR Error", e);
    } finally {
      if (ocrTimeoutRef.current) clearTimeout(ocrTimeoutRef.current);
      isProcessingRef.current = false;
      if (Date.now() - lastProcessedTimeRef.current < 10000) {
        setOcrStatus('クールダウン');
      } else {
        setOcrStatus('待機中');
      }
    }
  }, effectiveScanInterval * 1000);

  // Low Frequency Party Scan Simulation (Runs every 10 scan cycles if enabled)
  useInterval(() => {
    if (isAutoRun && enablePartyScan && !isScanning) {
      // Simulate a quick background scan of the party
      setScanningMemberId(-1); // -1 indicates full party scan
      setTimeout(() => {
        setScanningMemberId(null);
      }, 800); // Brief visual feedback
    }
  }, effectiveScanInterval * 1000 * 10);

  // OCR Scan Mock with New Specs
  const handleOCRScan = (memberId: number) => {
    setScanningMemberId(memberId);
    // 1. Wait for level up animation lag
    setTimeout(() => {
      // 2. Retry simulation
      let retries = 0;
      const tryScan = () => {
        if (retries >= ocrRetryCount) {
          setScanningMemberId(null);
          // Simulate data confirmation after dictionary correction
          setParty(prev => prev.map(m => 
            m.id === memberId 
              ? { ...m, currentExp: Math.max(0, Math.floor(m.currentExp * 0.95)) }
              : m
          ));
          return;
        }
        retries++;
        setTimeout(tryScan, 500); // Retry interval
      };
      tryScan();
    }, ocrWaitTime * 1000);
  };

  // CSV Export
  const exportCSV = () => {
    try {
      const logsStr = localStorage.getItem('dq_macro_logs');
      const logs = logsStr ? JSON.parse(logsStr) : [];
      
      if (logs.length === 0) {
        alert('出力するログがありません。');
        return;
      }

      const header = "日時,キャラ1,キャラ1_EXP,キャラ2,キャラ2_EXP,キャラ3,キャラ3_EXP,キャラ4,キャラ4_EXP,累計討伐数\n";
      const rows = logs.map((log: any) => {
        return `"${log.timestamp}","${log.char1Name}",${log.char1Exp},"${log.char2Name}",${log.char2Exp},"${log.char3Name}",${log.char3Exp},"${log.char4Name}",${log.char4Exp},${log.totalKills}`;
      }).join('\n');
      
      const bom = new Uint8Array([0xEF, 0xBB, 0xBF]);
      const blob = new Blob([bom, header + rows], { type: 'text/csv;charset=utf-8;' });
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a');
      link.href = url;
      link.setAttribute('download', `log_${Date.now()}.csv`);
      document.body.appendChild(link);
      link.click();
      document.body.removeChild(link);
    } catch (e) {
      console.error(e);
      alert('ログの出力に失敗しました。');
    }
  };

  // Derived Statistics
  const avgExp = totalKills > 0 ? totalExp / totalKills : 0;
  const elapsedHours = (Date.now() - startTime) / 3600000;
  const expPerHour = elapsedHours > 0 ? Math.floor(totalExp / elapsedHours) : 0;

  const getBatteryIcon = () => {
    if (battery > 60) return <Battery size={14} className="text-emerald-400" />;
    if (battery > 20) return <BatteryMedium size={14} className="text-emerald-400" />;
    return <BatteryWarning size={14} className="text-red-500 animate-pulse" />;
  };

  const getTempIcon = () => {
    if (temperature === 'Hot') return <ThermometerSun size={14} className="text-red-500 animate-pulse" />;
    if (temperature === 'Warm') return <Thermometer size={14} className="text-yellow-500" />;
    return <Thermometer size={14} className="text-emerald-400" />;
  };

  const handleCalibrationClick = (e: React.MouseEvent<HTMLDivElement>) => {
    if (calibrationState === 'IDLE' || calibrationState === 'OCR_SEARCHING' || !videoRef.current || !stream) return;
    
    const rect = e.currentTarget.getBoundingClientRect();
    const xPct = (e.clientX - rect.left) / rect.width;
    const yPct = (e.clientY - rect.top) / rect.height;
    
    if (calibrationState === 'UNLOCK_BTN') {
      const color = checkPixelColor(videoRef.current, xPct, yPct);
      if (color) {
        setUnlockBtnPos({ x: xPct, y: yPct });
        setUnlockBtnColor(color);
        localStorage.setItem('unlockBtnPos', JSON.stringify({ x: xPct, y: yPct }));
        localStorage.setItem('unlockBtnColor', JSON.stringify(color));
        setCalibrationState('IDLE');
        alert('「解除する」ボタンのキャリブレーションが完了しました！\n次回以降はこの位置と色で判定します。');
      }
    } else if (calibrationState === 'CHAR_CENTER') {
      setCharCenterPos({ x: xPct, y: yPct });
      localStorage.setItem('charCenterPos', JSON.stringify({ x: xPct, y: yPct }));
      setCalibrationState('CIRCLE_EDGE');
    } else if (calibrationState === 'CIRCLE_EDGE') {
      // 画面幅に対する割合として半径を計算
      const dx = (xPct - charCenterPos.x);
      const dy = (yPct - charCenterPos.y) * (rect.height / rect.width); // アスペクト比補正
      const radiusPct = Math.sqrt(dx * dx + dy * dy);
      
      setCircleRadius(radiusPct);
      localStorage.setItem('circleRadius', radiusPct.toString());
      setCalibrationState('IDLE');
      alert('キャラクター位置とサークル範囲のキャリブレーションが完了しました！');
    } else if (calibrationState === 'BERSERKER_ICON') {
      setBerserkerIconPos({ x: xPct, y: yPct });
      localStorage.setItem('berserkerIconPos', JSON.stringify({ x: xPct, y: yPct }));
      setCalibrationState('BERSERKER_ITEM');
    } else if (calibrationState === 'BERSERKER_ITEM') {
      setBerserkerItemPos({ x: xPct, y: yPct });
      localStorage.setItem('berserkerItemPos', JSON.stringify({ x: xPct, y: yPct }));
      setCalibrationState('IDLE');
      alert('においぶくろのタップ位置を記憶しました！');
    }
  };

  const runAutoCalibration = async () => {
    if (!videoRef.current || !stream) {
      alert('カメラ/画面共有が開始されていません');
      return;
    }
    
    setCalibrationState('OCR_SEARCHING');
    
    try {
      const video = videoRef.current;
      const vw = video.videoWidth;
      const vh = video.videoHeight;
      
      // 画面下半分をキャプチャ
      const rect = { x: 0, y: vh * 0.6, w: vw, h: vh * 0.4 };
      const canvas = captureAndCrop(video, rect);
      if (!canvas) throw new Error('キャプチャ失敗');
      
      const worker = await createWorker('jpn');
      const { data } = await worker.recognize(canvas) as any;
      
      // 「解除する」を探す
      const targetWord = data.words.find((w: any) => w.text.includes('解除') || w.text.includes('する'));
      
      if (targetWord) {
        const bbox = targetWord.bbox;
        const centerX = bbox.x0 + (bbox.x1 - bbox.x0) / 2;
        const centerY = bbox.y0 + (bbox.y1 - bbox.y0) / 2;
        
        const globalX = rect.x + centerX;
        const globalY = rect.y + centerY;
        
        const xPct = globalX / vw;
        const yPct = globalY / vh;
        
        const color = checkPixelColor(video, xPct, yPct);
        
        if (color) {
          setUnlockBtnPos({ x: xPct, y: yPct });
          setUnlockBtnColor(color);
          localStorage.setItem('unlockBtnPos', JSON.stringify({ x: xPct, y: yPct }));
          localStorage.setItem('unlockBtnColor', JSON.stringify(color));
          alert('「解除する」ボタンを自動検出しました！');
        }
      } else {
        alert('「解除する」ボタンが見つかりませんでした。手動設定をお試しください。');
      }
      
      await worker.terminate();
    } catch (err) {
      console.error(err);
      alert('OCR解析中にエラーが発生しました。');
    } finally {
      setCalibrationState('IDLE');
    }
  };

  return (
    <div className="min-h-screen bg-black sm:py-8 flex items-center justify-center">
      <div ref={constraintsRef} className="w-full max-w-[400px] h-[100dvh] sm:h-[800px] sm:rounded-[40px] relative overflow-hidden bg-[#050505] text-gray-200 font-mono selection:bg-emerald-500/30 shadow-[0_0_50px_rgba(0,0,0,0.5)] sm:border-[8px] sm:border-[#111]">
        {stream && (
          <video
            ref={videoRef}
            autoPlay
            playsInline
            muted
            className="absolute inset-0 w-full h-full object-cover opacity-40 pointer-events-none z-0"
          />
        )}
        <div className="absolute inset-0 bg-grid opacity-20 pointer-events-none z-0" />
      
      {/* Calibration Overlay */}
      {calibrationState !== 'IDLE' && (
        <div 
          className={`absolute inset-0 z-[100] bg-black/50 flex flex-col items-center justify-center ${calibrationState === 'OCR_SEARCHING' ? 'cursor-wait' : 'cursor-crosshair'}`}
          onClick={handleCalibrationClick}
        >
          {calibrationState !== 'OCR_SEARCHING' && (
            <button 
              onClick={(e) => { e.stopPropagation(); setCalibrationState('IDLE'); }}
              className="absolute top-6 right-6 p-3 bg-[#111] text-gray-400 rounded-full border border-[#333] hover:bg-[#222] hover:text-white transition-colors shadow-lg z-[110]"
              title="キャンセル"
            >
              <X size={24} />
            </button>
          )}
          <div className="bg-[#111] border border-emerald-500/50 p-4 rounded-xl text-center shadow-2xl pointer-events-none mb-32 max-w-[80%]">
            <div className="text-emerald-400 font-bold mb-2">
              {calibrationState === 'OCR_SEARCHING' ? 'OCR自動解析中...' : 'キャリブレーション中'}
            </div>
            <div className="text-xs text-gray-300 leading-relaxed whitespace-pre-wrap">
              {calibrationState === 'OCR_SEARCHING' && '画面から「解除する」ボタンを探しています。\nしばらくお待ちください。'}
              {calibrationState === 'UNLOCK_BTN' && 'ゲーム画面の「解除する」ボタンの\n中心をタップしてください。'}
              {calibrationState === 'CHAR_CENTER' && 'キャラクターの足元（サークルの中心）を\nタップしてください。'}
              {calibrationState === 'CIRCLE_EDGE' && 'サークルの白い線（円周）のどこかを\nタップしてください。'}
              {calibrationState === 'BERSERKER_ICON' && 'メイン画面の「匂い袋アイコン」を\nタップしてください。'}
              {calibrationState === 'BERSERKER_ITEM' && 'アイテム選択画面の「においぶくろ」を\nタップしてください。'}
            </div>
          </div>
        </div>
      )}

      {/* Tap Visualizer */}
      {taps.map(tap => (
        <div
          key={tap.id}
          className="absolute w-12 h-12 bg-white/40 rounded-full pointer-events-none animate-ping z-[60]"
          style={{
            left: `${tap.x * 100}%`,
            top: `${tap.y * 100}%`,
            transform: 'translate(-50%, -50%)'
          }}
        />
      ))}

      {/* Target Indicator */}
      {stream && (
        <>
          {/* Unlock Btn */}
          <div 
            className="absolute w-1 h-1 bg-red-500 rounded-full pointer-events-none z-10 opacity-30"
            style={{ 
              left: `${unlockBtnPos.x * 100}%`, 
              top: `${unlockBtnPos.y * 100}%`,
              transform: 'translate(-50%, -50%)'
            }}
          />
          
          {/* Char Center & Circle */}
          {calibrationState === 'IDLE' && (
            <div 
              className="absolute pointer-events-none z-10 w-full"
              style={{ 
                left: 0, 
                top: `${charCenterPos.y * 100}%`,
                transform: 'translateY(-50%)'
              }}
            >
              {/* Center Dot */}
              <div 
                className="absolute w-1.5 h-1.5 bg-purple-500 rounded-full transform -translate-x-1/2 -translate-y-1/2 opacity-80 shadow-[0_0_5px_rgba(168,85,247,0.8)]" 
                style={{ left: `${charCenterPos.x * 100}%` }}
              />
              {/* Main Circle */}
              <div 
                className="absolute border-2 border-purple-500/40 rounded-full transform -translate-x-1/2 -translate-y-1/2"
                style={{ 
                  left: `${charCenterPos.x * 100}%`,
                  width: `${circleRadius * 2 * 100}%`, 
                  paddingBottom: `${circleRadius * 2 * 100}%`,
                  height: 0
                }}
              />
              {/* Pull Margin Circle */}
              <div 
                className="absolute border-2 border-dashed border-emerald-500/40 rounded-full transform -translate-x-1/2 -translate-y-1/2"
                style={{ 
                  left: `${charCenterPos.x * 100}%`,
                  width: `${circleRadius * pullMargin * 2 * 100}%`, 
                  paddingBottom: `${circleRadius * pullMargin * 2 * 100}%`,
                  height: 0
                }}
              />
            </div>
          )}
        </>
      )}

      {isEcoMode && (
        <div className="absolute inset-0 bg-black/80 pointer-events-none z-20 transition-opacity duration-1000" />
      )}
      
      {isScanning && scanningMemberId !== -1 && <div className="scan-line z-50" />}
      {isScanning && scanningMemberId === -1 && (
        <div className="absolute inset-0 bg-emerald-500/5 z-40 pointer-events-none flex items-center justify-center">
          <div className="bg-black/80 text-emerald-400 px-4 py-2 rounded-full border border-emerald-500/30 text-xs font-bold flex items-center gap-2">
            <Scan size={14} className="animate-spin" />
            PARTY SYNC
          </div>
        </div>
      )}

      {/* Main HUD */}
      <motion.div
        drag
        dragConstraints={isDragConstrained ? constraintsRef : false}
        dragMomentum={false}
        dragElastic={0}
        onClick={() => {
          if (displayMode === 'all') setDisplayMode('stats');
          else if (displayMode === 'stats') setDisplayMode('party');
          else setDisplayMode('all');
        }}
        className={`absolute top-6 left-4 bg-[#0a0a0a] rounded-2xl w-[340px] flex flex-col z-30 transition-opacity duration-1000 border border-[#222] shadow-2xl cursor-pointer ${
          isEcoMode ? 'opacity-60' : ''
        }`}
        animate={{ scale: uiScale }}
        transition={{ scale: { type: "spring", bounce: 0, duration: 0.4 } }}
      >
        {/* Drag Handle */}
        <div className="w-full flex justify-center pt-3 pb-1 cursor-move text-gray-600 hover:text-gray-400">
          <GripHorizontal size={18} />
        </div>

        <div className="px-6 pb-6 pt-2">
          {/* Top Row: Clock, SYS, Temp, Battery */}
          <div className="flex justify-between items-start mb-5">
            <div className="text-2xl font-bold tracking-widest text-white leading-none mt-1">{time}</div>
            <div className="flex items-start gap-4 text-[11px] font-medium text-gray-300">
              <div className="flex flex-col items-center gap-1">
                <div className="flex items-center gap-1.5">
                  <div className={`w-2 h-2 rounded-full ${isAutoRun ? (appStatus === 'WALK_MODE' ? 'bg-blue-400 animate-pulse shadow-[0_0_8px_rgba(96,165,250,0.8)]' : 'bg-amber-400 animate-pulse shadow-[0_0_8px_rgba(251,191,36,0.8)]') : 'bg-emerald-500 shadow-[0_0_8px_rgba(16,185,129,0.8)]'}`} />
                  <span>SYS</span>
                </div>
                {isAutoRun && <span className={`text-[9px] border px-1 rounded ${appStatus === 'WALK_MODE' ? 'text-blue-400 border-blue-500/50' : 'text-amber-400 border-amber-500/50'}`}>{appStatus === 'WALK_MODE' ? 'WALK' : 'WAIT'}</span>}
              </div>
              <div className="flex items-center gap-1 mt-0.5">
                {getTempIcon()}
                <span className={temperature === 'Hot' ? 'text-red-500' : temperature === 'Warm' ? 'text-yellow-500' : 'text-gray-300'}>{temperature}</span>
              </div>
              <div className="flex items-center gap-1 mt-0.5">
                {getBatteryIcon()}
                <span className={battery < 20 ? 'text-red-500' : 'text-gray-300'}>{battery.toFixed(0)}%</span>
              </div>
            </div>
          </div>

          {/* Stats Row */}
          {(displayMode === 'all' || displayMode === 'stats') && (
            <div className={`flex border border-[#222] rounded-xl bg-[#111] overflow-hidden ${displayMode === 'all' ? 'mb-5' : ''}`}>
              <div className="flex-1 p-3 text-center border-r border-[#222]">
                <div className="text-[11px] text-gray-400 font-bold tracking-widest mb-1">KILL</div>
                <div className="text-2xl font-bold text-emerald-400 mb-1">{totalKills.toLocaleString()}</div>
                <div className="text-[11px] text-gray-500">Avg: {avgExp.toLocaleString(undefined, {maximumFractionDigits: 0})}</div>
              </div>
              <div className="flex-1 p-3 text-center">
                <div className="text-[11px] text-gray-400 font-bold tracking-widest mb-1">EXP</div>
                <div className="text-2xl font-bold text-emerald-400 mb-1">{totalExp.toLocaleString()}</div>
                <div className="text-[11px] text-gray-500">{expPerHour.toLocaleString()} /h</div>
              </div>
            </div>
          )}

          {/* Party List */}
          {(displayMode === 'all' || displayMode === 'party') && (
            <div className="space-y-5">
              {party.map(member => (
              <div key={member.id} className="relative">
                <div className="flex justify-between items-baseline mb-2">
                  <div className="flex items-center gap-2">
                    <div className="w-[18px] h-[18px] rounded bg-emerald-900/40 text-emerald-400 flex items-center justify-center text-[10px] font-bold border border-emerald-800/50">
                      {member.id}
                    </div>
                    <span className="font-bold text-[15px] text-white tracking-wide">{member.name}</span>
                    <span className="text-[11px] text-gray-500">({member.job})</span>
                  </div>
                  <span className="text-[13px] font-bold text-emerald-400 tracking-wider">Lv.{member.level}</span>
                </div>
                <div className="h-1.5 bg-[#222] rounded-full overflow-hidden mb-1.5">
                  <motion.div 
                    className="h-full bg-emerald-500"
                    initial={{ width: 0 }}
                    animate={{ width: `${(member.currentExp / member.nextExp) * 100}%` }}
                    transition={{ duration: 0.3 }}
                  />
                </div>
                <div className="text-right text-[10px] text-gray-500 tracking-wider">
                  Next: {(member.nextExp - member.currentExp).toLocaleString()}
                </div>
              </div>
            ))}
          </div>
          )}
        </div>
      </motion.div>

      {/* Floating Controls Widget */}
      <motion.div
        drag
        dragConstraints={isDragConstrained ? constraintsRef : false}
        dragMomentum={false}
        dragElastic={0}
        className="absolute bottom-8 right-6 bg-[#0a0a0a] rounded-full flex items-center p-1.5 z-30 border border-[#222] shadow-2xl"
      >
        <div className="px-3 cursor-move text-gray-600 hover:text-gray-400">
          <GripHorizontal size={18} />
        </div>
        
        {isAutoBattleEnabled && (
          <>
            <div className="w-px h-6 bg-[#333] mx-1" />
            <button 
              onClick={() => setIsAutoRun(!isAutoRun)}
              className={`p-3 rounded-full transition-colors ${isAutoRun ? 'text-emerald-400 bg-emerald-900/20' : 'text-gray-400 hover:text-white hover:bg-[#222]'}`}
              title={isAutoRun ? "Stop Auto Run" : "Start Auto Run"}
            >
              {isAutoRun ? <Square size={18} /> : <Play size={18} />}
            </button>
            <button 
              onClick={() => {
                if (!isAutoRun) {
                  runAutoCalibration();
                }
              }}
              className={`p-3 rounded-full transition-colors ${isAutoRun ? 'text-gray-600 cursor-not-allowed' : 'text-blue-400 hover:text-blue-300 hover:bg-blue-900/20'}`}
              title="WALKモード自動サーチ"
              disabled={isAutoRun}
            >
              <LocateFixed size={18} />
            </button>
          </>
        )}
        
        <div className="w-px h-6 bg-[#333] mx-1" />

        <button 
          onClick={() => setShowOCRWindow(true)}
          className="p-3 rounded-full text-gray-400 hover:text-white hover:bg-[#222] transition-colors"
          title="OCR Sync"
        >
          <ScanText size={18} />
        </button>

        <button 
          onClick={() => setShowSettings(true)}
          className="p-3 rounded-full text-gray-400 hover:text-white hover:bg-[#222] transition-colors"
          title="Settings"
        >
          <Settings size={18} />
        </button>
      </motion.div>

      {/* Capture Warning Modal */}
      {showCaptureWarning && (
        <div className="absolute inset-0 z-[70] flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#0a0a0a] p-6 rounded-2xl w-full max-w-sm text-gray-200 font-mono border border-emerald-900/50 shadow-2xl">
            <h2 className="text-lg font-bold mb-4 flex items-center gap-2 text-emerald-400 border-b border-[#333] pb-2">
              <AlertTriangle size={18}/> 画面キャプチャの許可
            </h2>
            <div className="space-y-3 text-xs text-gray-300 mb-6 leading-relaxed">
              <p>
                この機能は、ゲーム画面をアプリの背景に表示し、オーバーレイ表示のように見せるためのものです。
              </p>
              <ul className="list-disc pl-4 space-y-1 text-gray-400">
                <li>次の画面で「ゲームのウィンドウ」または「画面全体」を選択して許可してください。</li>
                <li className="text-amber-400 font-bold">画面上に個人情報や通知が映り込まないよう十分ご注意ください。</li>
                <li>取得した画面データはブラウザ内でのみ表示され、外部サーバー等には一切送信されません。</li>
              </ul>
            </div>
            
            <div className="flex gap-3">
              <button
                onClick={() => setShowCaptureWarning(false)}
                className="flex-1 py-3 bg-[#222] hover:bg-[#333] text-gray-300 rounded-lg font-bold transition-colors"
              >
                キャンセル
              </button>
              <button
                onClick={startCapture}
                className="flex-1 py-3 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg font-bold transition-colors"
              >
                許可して開始
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Reset Confirm Modal */}
      {showResetConfirm && (
        <div className="absolute inset-0 z-[70] flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#0a0a0a] p-6 rounded-2xl w-full max-w-sm text-gray-200 font-mono border border-red-900/50 shadow-2xl">
            <h2 className="text-lg font-bold mb-4 flex items-center gap-2 text-red-400 border-b border-[#333] pb-2">
              <AlertTriangle size={18}/> データリセットの確認
            </h2>
            <div className="space-y-3 text-sm text-gray-300 mb-6 leading-relaxed">
              <p>
                総EXPとKill数を0にリセットします。<br/>
                この操作は取り消せません。よろしいですか？
              </p>
            </div>
            
            <div className="flex gap-3">
              <button
                onClick={() => setShowResetConfirm(false)}
                className="flex-1 py-3 bg-[#222] hover:bg-[#333] text-gray-300 rounded-lg font-bold transition-colors"
              >
                キャンセル
              </button>
              <button
                onClick={() => {
                  setTotalExp(0);
                  setTotalKills(0);
                  setShowResetConfirm(false);
                }}
                className="flex-1 py-3 bg-red-600 hover:bg-red-500 text-white rounded-lg font-bold transition-colors"
              >
                リセットする
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Settings Modal */}
      {showSettings && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#0a0a0a] p-6 rounded-2xl w-full max-h-full overflow-y-auto text-gray-200 font-mono border border-[#333] shadow-2xl custom-scrollbar">
            <h2 className="text-lg font-bold mb-6 flex items-center gap-2 text-white sticky top-0 bg-[#0a0a0a] pb-2 z-10 border-b border-[#333]">
              <Settings size={18}/> システム設定
            </h2>
            
            <div className="space-y-6">
              {/* Feature Toggles */}
              <div className="space-y-3 border-b border-[#333] pb-4">
                <div>
                  <div className="flex items-center justify-between">
                    <label className="text-xs text-gray-400">自動戦闘機能</label>
                    <button 
                      onClick={() => {
                        setIsAutoBattleEnabled(!isAutoBattleEnabled);
                        if (isAutoBattleEnabled) setIsAutoRun(false);
                      }}
                      className={`px-3 py-1 rounded text-xs font-bold ${isAutoBattleEnabled ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                    >
                      {isAutoBattleEnabled ? 'ON' : 'OFF'}
                    </button>
                  </div>
                  {Capacitor.isNativePlatform() && (
                    <button 
                      onClick={async () => {
                        try {
                          await OverlayPlugin.startOverlay();
                          // オーバーレイ起動後に現在の設定を送信
                          await OverlayPlugin.updateSettings({
                            targetKeyword: tapPriorities[0].label,
                            isAutoBattleEnabled: isAutoBattleEnabled,
                            tapOffsetX: 0,
                            tapOffsetY: targetTapOffset,
                            scanInterval: scanInterval,
                            enableResultDetection: enableResultDetection,
                          });
                        } catch (e) {
                          console.error('Failed to start overlay', e);
                        }
                      }}
                      className="mt-2 w-full px-3 py-2 rounded bg-blue-900/40 text-blue-400 border border-blue-800 text-xs font-bold flex items-center justify-center gap-2"
                    >
                      <Monitor size={14} /> Android オーバーレイを開始
                    </button>
                  )}
                  <details className="group mt-1.5">
                    <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                      <HelpCircle size={10} /> Tips
                    </summary>
                    <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                      ONにすると、画面上の敵を自動でタップして戦闘を開始します。
                    </div>
                  </details>
                </div>
                <div>
                  <div className="flex items-center justify-between">
                    <label className="text-xs text-gray-400">エコモード</label>
                    <button 
                      onClick={() => setIsEcoMode(!isEcoMode)}
                      className={`px-3 py-1 rounded text-xs font-bold ${isEcoMode ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                    >
                      {isEcoMode ? 'ON' : 'OFF'}
                    </button>
                  </div>
                  <details className="group mt-1.5">
                    <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                      <HelpCircle size={10} /> Tips
                    </summary>
                    <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                      画面の描画頻度を下げてバッテリー消費を抑えます。
                    </div>
                  </details>
                </div>
              </div>

              {/* OCR & Scan Settings */}
              <div className="border-b border-[#333] pb-4">
                <button 
                  onClick={() => setIsOcrSettingsExpanded(!isOcrSettingsExpanded)}
                  className="w-full flex items-center justify-between text-emerald-400 font-bold text-xs py-2 hover:bg-[#111] rounded px-2 -mx-2 transition-colors"
                >
                  <span>OCR & スキャン設定</span>
                  {isOcrSettingsExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                </button>
                
                {isOcrSettingsExpanded && (
                  <div className="space-y-4 pt-3 mt-2 border-t border-[#222]">
                    <div className="bg-[#111] p-3 rounded-lg border border-[#333]">
                      <div className="flex items-center justify-between mb-2">
                        <label className="text-xs text-gray-300 font-bold">WALKモード判定位置の調整</label>
                        {!Capacitor.isNativePlatform() ? (
                          <div className="flex gap-2">
                            <button 
                              onClick={() => {
                                setShowSettings(false);
                                runAutoCalibration();
                              }}
                              className="px-2 py-1 bg-emerald-900/40 text-emerald-400 border border-emerald-800 rounded text-[10px] font-bold hover:bg-emerald-900/60 transition-colors"
                            >
                              OCR自動設定
                            </button>
                            <button 
                              onClick={() => {
                                setShowSettings(false);
                                setCalibrationState('UNLOCK_BTN');
                              }}
                              className="px-2 py-1 bg-blue-900/40 text-blue-400 border border-blue-800 rounded text-[10px] font-bold hover:bg-blue-900/60 transition-colors"
                            >
                              手動設定
                            </button>
                          </div>
                        ) : (
                          <span className="text-[10px] text-emerald-400 border border-emerald-800 bg-emerald-900/40 px-2 py-1 rounded">ネイティブ連携中</span>
                        )}
                      </div>
                      <p className="text-[10px] text-gray-500 leading-relaxed">
                        {Capacitor.isNativePlatform() 
                          ? 'Android版では、オーバーレイ上の「Set Scan Area」「Set Tap Point」ボタンを使用して判定位置を調整してください。'
                          : 'ゲーム画面の「解除する」ボタンの位置と色を記憶させ、自動戦闘の監視精度を100%にします。'}
                      </p>
                    </div>

                    {/* キャラクター＆サークル設定 */}
                    <div className="bg-[#111] p-3 rounded-lg border border-[#333]">
                      <div className="flex items-center justify-between mb-2">
                        <label className="text-xs text-gray-300 font-bold">キャラクター位置＆サークル範囲</label>
                        {!Capacitor.isNativePlatform() ? (
                          <button 
                            onClick={() => {
                              setShowSettings(false);
                              setCalibrationState('CHAR_CENTER');
                            }}
                            className="px-3 py-1 bg-purple-900/40 text-purple-400 border border-purple-800 rounded text-xs font-bold hover:bg-purple-900/60 transition-colors"
                          >
                            設定する
                          </button>
                        ) : (
                          <span className="text-[10px] text-purple-400 border border-purple-800 bg-purple-900/40 px-2 py-1 rounded">ネイティブ連携中</span>
                        )}
                      </div>
                      <p className="text-[10px] text-gray-500 leading-relaxed mb-3">
                        {Capacitor.isNativePlatform()
                          ? 'Android版では、オーバーレイ上の設定からタップ位置を調整してください。'
                          : 'キャラクターの足元と、サークルの大きさを記憶させます。'}
                      </p>
                      
                      <div className="space-y-2 border-t border-[#222] pt-3">
                        <div className="flex items-center justify-between">
                          <label className="text-xs text-gray-400">引き寄せマージン (指一本分)</label>
                          <span className="text-xs font-mono text-emerald-400">x{pullMargin.toFixed(2)}</span>
                        </div>
                        <input 
                          type="range" 
                          min="1.0" max="2.0" step="0.05"
                          value={pullMargin}
                          onChange={(e) => {
                            const val = parseFloat(e.target.value);
                            setPullMargin(val);
                            localStorage.setItem('pullMargin', val.toString());
                          }}
                          className="w-full accent-emerald-500"
                        />
                        <p className="text-[10px] text-gray-500">
                          サークルの外側、どのくらいの範囲までタップして寄せるかを設定します。（1.0でサークル内のみ）
                        </p>
                      </div>
                    </div>

                    <div>
                      <div className="flex items-center justify-between">
                        <label className="text-xs text-gray-400">リザルト検知</label>
                        <button 
                          onClick={() => setEnableResultDetection(!enableResultDetection)}
                          className={`px-3 py-1 rounded text-xs font-bold ${enableResultDetection ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                        >
                          {enableResultDetection ? 'ON' : 'OFF'}
                        </button>
                      </div>
                      <details className="group mt-1.5">
                        <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                          <HelpCircle size={10} /> Tips
                        </summary>
                        <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                          戦闘終了（リザルト画面）を検知して、自動で画面をタップして次の行動に移ります。
                        </div>
                      </details>
                    </div>

                    <div>
                      <div className="flex items-center justify-between">
                        <label className="text-xs text-gray-400">アンカーキーワード検索</label>
                        <button 
                          onClick={() => setEnableAnchorSearch(!enableAnchorSearch)}
                          className={`px-3 py-1 rounded text-xs font-bold ${enableAnchorSearch ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                        >
                          {enableAnchorSearch ? 'ON' : 'OFF'}
                        </button>
                      </div>
                      <details className="group mt-1.5">
                        <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                          <HelpCircle size={10} /> Tips
                        </summary>
                        <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                          特定の画像（アンカー）を基準にして検索範囲を絞り込み、誤タップを防ぎます。
                        </div>
                      </details>
                    </div>

                    <div>
                      <label className="block text-xs text-gray-400 mb-2">ターゲットタップ位置微調整 (%)</label>
                      <input 
                        type="range" 
                        min="-30" max="30" step="1"
                        value={targetTapOffset}
                        onChange={(e) => {
                          setTargetTapOffset(Number(e.target.value));
                          localStorage.setItem('targetTapOffset', e.target.value);
                        }}
                        className="w-full accent-emerald-500"
                      />
                      <div className="text-right text-xs mt-1 text-emerald-400">{targetTapOffset}%</div>
                      <p className="text-[10px] text-gray-500">
                        キャラクター位置からのY軸オフセットを設定します。（マイナスで上方向）
                      </p>
                    </div>

                    <div>
                      <label className="block text-xs text-gray-400 mb-2">ワイドスキャン領域 (%)</label>
                      <input 
                        type="range" 
                        min="60" max="90" step="5"
                        value={wideScanArea}
                        onChange={(e) => setWideScanArea(Number(e.target.value))}
                        className="w-full accent-emerald-500"
                      />
                      <div className="text-right text-xs mt-1 text-emerald-400">{wideScanArea}%</div>
                      <details className="group mt-1.5">
                        <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                          <HelpCircle size={10} /> Tips
                        </summary>
                        <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                          画面全体を広くスキャンするかどうかの設定です。数値を上げるとより広い範囲を探します。
                        </div>
                      </details>
                    </div>

                    <div>
                      <div className="flex items-center justify-between">
                        <label className="text-xs text-gray-400">正規表現フィルタ (\d+)(exp)</label>
                        <button 
                          onClick={() => setEnableRegexFilter(!enableRegexFilter)}
                          className={`px-3 py-1 rounded text-xs font-bold ${enableRegexFilter ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                        >
                          {enableRegexFilter ? 'ON' : 'OFF'}
                        </button>
                      </div>
                      <details className="group mt-1.5">
                        <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                          <HelpCircle size={10} /> Tips
                        </summary>
                        <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                          モンスター名などを正規表現でフィルタリングし、特定の敵だけを狙うことができます。
                        </div>
                      </details>
                    </div>

                    <div>
                      <div className="flex items-center justify-between">
                        <label className="text-xs text-gray-400">辞書補正</label>
                        <button 
                          onClick={() => setEnableDictCorrection(!enableDictCorrection)}
                          className={`px-3 py-1 rounded text-xs font-bold ${enableDictCorrection ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                        >
                          {enableDictCorrection ? 'ON' : 'OFF'}
                        </button>
                      </div>
                      <details className="group mt-1.5">
                        <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                          <HelpCircle size={10} /> Tips
                        </summary>
                        <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                          OCRで読み取ったテキストの誤字を自動で修正します。
                        </div>
                      </details>
                    </div>

                    <div>
                      <label className="block text-xs text-gray-400 mb-2">待機時間 (秒)</label>
                      <input 
                        type="range" 
                        min="0" max="3" step="0.5"
                        value={ocrWaitTime}
                        onChange={(e) => setOcrWaitTime(Number(e.target.value))}
                        className="w-full accent-emerald-500"
                      />
                      <div className="text-right text-xs mt-1 text-emerald-400">{ocrWaitTime.toFixed(1)}s</div>
                      <details className="group mt-1.5">
                        <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                          <HelpCircle size={10} /> Tips
                        </summary>
                        <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                          画面が切り替わってからスキャンを開始するまでの待機時間です。
                        </div>
                      </details>
                    </div>

                    <div>
                      <label className="block text-xs text-gray-400 mb-2">最大リトライ回数</label>
                      <input 
                        type="range" 
                        min="1" max="5" step="1"
                        value={ocrRetryCount}
                        onChange={(e) => setOcrRetryCount(Number(e.target.value))}
                        className="w-full accent-emerald-500"
                      />
                      <div className="text-right text-xs mt-1 text-emerald-400">{ocrRetryCount}</div>
                      <details className="group mt-1.5">
                        <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                          <HelpCircle size={10} /> Tips
                        </summary>
                        <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                          スキャンに失敗した際に、最大何回まで再試行するかを設定します。
                        </div>
                      </details>
                    </div>

                    <div>
                      <label className="block text-xs text-gray-400 mb-2">スキャン間隔 (秒)</label>
                      <input 
                        type="range" 
                        min="0.5" max="10" step="0.5"
                        value={scanInterval}
                        onChange={(e) => setScanInterval(Number(e.target.value))}
                        className="w-full accent-emerald-500"
                      />
                      <div className="text-right text-xs mt-1 text-emerald-400">{scanInterval.toFixed(1)}s</div>
                      <details className="group mt-1.5">
                        <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                          <HelpCircle size={10} /> Tips
                        </summary>
                        <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                          定期的に画面をスキャンする間隔です。短くすると反応が早くなりますが、負荷が上がります。
                        </div>
                      </details>
                    </div>
                  </div>
                )}
              </div>

              {/* Party Sync */}
              <div className="border-b border-[#333] pb-4 space-y-3">
                <div className="text-emerald-400 font-bold text-xs mb-2">パーティ同期</div>
                <div>
                  <div className="flex items-center justify-between">
                    <label className="text-xs text-gray-400">パーティ画面スキャン</label>
                    <button 
                      onClick={() => setEnablePartyScan(!enablePartyScan)}
                      className={`px-3 py-1 rounded text-xs font-bold ${enablePartyScan ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                    >
                      {enablePartyScan ? 'ON' : 'OFF'}
                    </button>
                  </div>
                  <details className="group mt-1.5">
                    <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                      <HelpCircle size={10} /> Tips
                    </summary>
                    <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                      パーティのHP/MPなどを監視し、ピンチの時に自動で回復などの行動をとる機能です。
                    </div>
                  </details>
                </div>
              </div>

              {/* Battle Control */}
              <div className="border-b border-[#333] pb-4 space-y-3">
                <div className="text-emerald-400 font-bold text-xs mb-2">バトル制御</div>
                <div className="space-y-2">
                  {/* バーサーカーモード */}
                  <div className="bg-[#111] p-3 rounded-lg border border-[#222]">
                    <div className="flex justify-between items-center mb-2">
                      <div>
                        <div className="text-sm text-gray-200 font-bold">Aggressive Aromatherapy</div>
                        <div className="text-[10px] text-gray-500">(良いニオイがする)</div>
                      </div>
                      <label className="relative inline-flex items-center cursor-pointer">
                        <input 
                          type="checkbox" 
                          className="sr-only peer"
                          checked={isBerserkerMode}
                          onChange={(e) => {
                            setIsBerserkerMode(e.target.checked);
                            localStorage.setItem('isBerserkerMode', String(e.target.checked));
                          }}
                        />
                        <div className="w-9 h-5 bg-[#333] peer-focus:outline-none rounded-full peer peer-checked:after:translate-x-full peer-checked:after:border-white after:content-[''] after:absolute after:top-[2px] after:left-[2px] after:bg-white after:border-gray-300 after:border after:rounded-full after:h-4 after:w-4 after:transition-all peer-checked:bg-emerald-500"></div>
                      </label>
                    </div>
                    <div className="flex justify-between items-center mt-3 pt-3 border-t border-[#222]">
                      <span className="text-xs text-gray-400">タップ位置の記憶</span>
                      <button 
                        onClick={() => {
                          setShowSettings(false);
                          setCalibrationState('BERSERKER_ICON');
                        }}
                        className="px-3 py-1.5 bg-[#222] text-gray-300 rounded text-xs font-bold hover:bg-[#333] transition-colors"
                      >
                        設定する
                      </button>
                    </div>
                  </div>

                  <div className="flex items-center justify-between">
                    <label className="text-xs text-gray-400">つぼフィルタ (OpenCV)</label>
                    <button 
                      onClick={() => setEnablePotFilter(!enablePotFilter)}
                      className={`px-3 py-1 rounded text-xs font-bold ${enablePotFilter ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                    >
                      {enablePotFilter ? 'ON' : 'OFF'}
                    </button>
                  </div>
                  <div>
                    <div className="flex items-center justify-between">
                      <label className="text-xs text-gray-400">バトル中スキャン一時停止</label>
                      <button 
                        onClick={() => setPauseScanOnBattle(!pauseScanOnBattle)}
                        className={`px-3 py-1 rounded text-xs font-bold ${pauseScanOnBattle ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        {pauseScanOnBattle ? 'ON' : 'OFF'}
                      </button>
                    </div>
                    <details className="group mt-1.5">
                      <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                        <HelpCircle size={10} /> Tips
                      </summary>
                      <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                        戦闘画面に入ったことを検知し、一時的に画面スキャン（タップ動作）を停止して負荷を下げます。
                      </div>
                    </details>
                  </div>

                  <div className="pt-2 border-t border-[#333]">
                    <div className="text-xs text-gray-400 mb-2">タップ対象フィルタ</div>
                    <div className="grid grid-cols-2 gap-2">
                      <button 
                        onClick={() => setTargetMetal(!targetMetal)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between ${targetMetal ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <span>メタルモンスター</span>
                        <span>{targetMetal ? 'ON' : 'OFF'}</span>
                      </button>
                      <button 
                        onClick={() => setTargetKakutei(!targetKakutei)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between ${targetKakutei ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <span>かくてい！</span>
                        <span>{targetKakutei ? 'ON' : 'OFF'}</span>
                      </button>
                      <button 
                        onClick={() => setTargetKoukaku(!targetKoukaku)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between ${targetKoukaku ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <span>こうかく！</span>
                        <span>{targetKoukaku ? 'ON' : 'OFF'}</span>
                      </button>
                      <button 
                        onClick={() => setTargetNormalEnemy(!targetNormalEnemy)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between ${targetNormalEnemy ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <span>！ 通常の敵</span>
                        <span>{targetNormalEnemy ? 'ON' : 'OFF'}</span>
                      </button>
                      <button 
                        onClick={() => setTargetStrongEnemy(!targetStrongEnemy)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between ${targetStrongEnemy ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <span>どこでも 強敵</span>
                        <span>{targetStrongEnemy ? 'ON' : 'OFF'}</span>
                      </button>
                      <button 
                        onClick={() => setTargetEventPop(!targetEventPop)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between ${targetEventPop ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <span>··· イベントポップ</span>
                        <span>{targetEventPop ? 'ON' : 'OFF'}</span>
                      </button>
                      <button 
                        onClick={() => setTargetPot(!targetPot)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between ${targetPot ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <span>壺</span>
                        <span>{targetPot ? 'ON' : 'OFF'}</span>
                      </button>
                      <button 
                        onClick={() => setTargetHokora(!targetHokora)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between ${targetHokora ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <div className="flex flex-col items-start">
                          <span>ほこら</span>
                          <span className="text-[7px] font-normal text-emerald-500/80">※建物オブジェクトとして認識</span>
                        </div>
                        <span>{targetHokora ? 'ON' : 'OFF'}</span>
                      </button>
                      <button 
                        onClick={() => setTargetOther(!targetOther)}
                        className={`px-2 py-1.5 rounded text-[10px] font-bold flex items-center justify-between col-span-2 ${targetOther ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                      >
                        <span>※ それ以外</span>
                        <span>{targetOther ? 'ON' : 'OFF'}</span>
                      </button>
                    </div>
                    <details className="group mt-2">
                      <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                        <HelpCircle size={10} /> Tips
                      </summary>
                      <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                        スキャンしてタップする対象（モンスター、壺、ほこらなど）を個別にON/OFFできます。
                      </div>
                    </details>
                  </div>

                  <div className="pt-3 border-t border-[#333]">
                    <div className="text-xs text-gray-400 mb-2 flex items-center justify-between">
                      <span>タップ優先度</span>
                      <div className="flex bg-[#111] rounded p-0.5 border border-[#333]">
                        <button
                          onClick={() => setActivePriorityIndex(1)}
                          className={`px-2 py-0.5 text-[10px] rounded font-bold transition-colors ${activePriorityIndex === 1 ? 'bg-emerald-900/40 text-emerald-400' : 'text-gray-500 hover:text-gray-300'}`}
                        >
                          設定1
                        </button>
                        <button
                          onClick={() => setActivePriorityIndex(2)}
                          className={`px-2 py-0.5 text-[10px] rounded font-bold transition-colors ${activePriorityIndex === 2 ? 'bg-emerald-900/40 text-emerald-400' : 'text-gray-500 hover:text-gray-300'}`}
                        >
                          設定2
                        </button>
                      </div>
                    </div>
                    <button
                      onClick={() => setShowPrioritySettings(true)}
                      className="w-full py-2 bg-[#222] border border-[#333] text-gray-300 rounded hover:bg-[#2a2a2a] hover:text-white transition-colors text-[10px] font-bold flex items-center justify-center gap-2"
                    >
                      <Settings size={14} />
                      タップ優先度を設定する
                    </button>
                    <details className="group mt-2">
                      <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                        <HelpCircle size={10} /> Tips
                      </summary>
                      <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                        画面内に複数の対象がいる場合、リストの上にあるものから優先的にタップします。
                      </div>
                    </details>
                  </div>
                </div>
              </div>

              {/* Data Logging */}
              <div className="border-b border-[#333] pb-4">
                <div className="text-emerald-400 font-bold text-xs mb-2">データロギング</div>
                <button 
                  onClick={exportCSV}
                  className="w-full py-2 bg-[#111] border border-[#333] text-gray-300 rounded hover:bg-[#222] hover:text-white transition-colors text-xs font-bold flex items-center justify-center gap-2 mb-3"
                >
                  <Download size={14} />
                  CSVエクスポート
                </button>
                <details className="group mt-1.5">
                  <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                    <HelpCircle size={10} /> Tips
                  </summary>
                  <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                    これまでの戦闘やスキャンの履歴データをCSV形式でダウンロードします。
                  </div>
                </details>
              </div>
              
              <div>
                <label className="block text-xs text-gray-400 mb-2">UIスケール</label>
                <input 
                  type="range" 
                  min="0.3" max={maxUiScale} step="0.05"
                  value={uiScale}
                  onChange={(e) => setUiScale(Number(e.target.value))}
                  className="w-full accent-emerald-500"
                />
                <div className="text-right text-xs mt-1 text-emerald-400">{uiScale.toFixed(2)}x (Max: {maxUiScale.toFixed(2)}x)</div>
                <details className="group mt-1.5">
                  <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                    <HelpCircle size={10} /> Tips
                  </summary>
                  <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                    操作パネルの大きさを変更します。画面サイズに合わせて調整してください。
                  </div>
                </details>
              </div>

              <div className="pt-4 mt-4 border-t border-[#333]">
                <div>
                  <div className="flex items-center justify-between">
                    <label className="text-xs text-gray-400">画面端のドラッグ移動制限</label>
                    <button 
                      onClick={() => setIsDragConstrained(!isDragConstrained)}
                      className={`px-3 py-1 rounded text-xs font-bold ${isDragConstrained ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                    >
                      {isDragConstrained ? 'ON' : 'OFF'}
                    </button>
                  </div>
                  <details className="group mt-1.5">
                    <summary className="text-[10px] text-gray-500 cursor-pointer hover:text-gray-400 transition-colors list-none [&::-webkit-details-marker]:hidden flex items-center gap-1">
                      <HelpCircle size={10} /> Tips
                    </summary>
                    <div className="text-[10px] text-gray-400 mt-1 pl-2 border-l-2 border-[#333] leading-relaxed">
                      操作パネルをドラッグして移動する際、画面外にはみ出さないように制限します。
                    </div>
                  </details>
                </div>
              </div>

              {/* Data Management */}
              <div className="pt-4 mt-4 border-t border-[#333]">
                <div>
                  <div className="flex items-center justify-between">
                    <label className="text-xs text-red-400 font-bold flex items-center gap-2">
                      <AlertTriangle size={14} /> データリセット
                    </label>
                    <button 
                      onClick={() => setShowResetConfirm(true)}
                      className="px-3 py-1 rounded text-xs font-bold bg-red-900/40 text-red-400 border border-red-800 hover:bg-red-900/60 transition-colors"
                    >
                      リセット
                    </button>
                  </div>
                  <p className="text-[10px] text-gray-500 mt-1">総EXPとKill数を0に戻します</p>
                </div>
              </div>

              {/* Overlay Settings */}
              <div className="pt-4 mt-4 border-t border-[#333]">
                <div>
                  <div className="flex items-center justify-between">
                    <label className="text-xs text-gray-400 font-bold flex items-center gap-2">
                      <Monitor size={14} /> オーバーレイ表示
                    </label>
                    <button 
                      onClick={() => stream ? stopCapture() : setShowCaptureWarning(true)}
                      className={`px-3 py-1 rounded text-xs font-bold ${stream ? 'bg-emerald-900/40 text-emerald-400 border border-emerald-800' : 'bg-[#222] text-gray-500 border border-[#333]'}`}
                    >
                      {stream ? 'ON' : 'OFF'}
                    </button>
                  </div>
                  <p className="text-[10px] text-gray-500 mt-1">ゲーム画面を背景に透かして表示します</p>
                </div>
              </div>
            </div>

            <button 
              onClick={() => setShowSettings(false)}
              className="mt-8 w-full py-3 bg-[#111] border border-[#333] text-white rounded-xl hover:bg-[#222] transition-colors font-bold text-sm tracking-widest sticky bottom-0"
            >
              閉じる
            </button>
          </div>
        </div>
      )}

      {/* Priority Settings Modal */}
      {showPrioritySettings && (
        <div className="absolute inset-0 z-[60] flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#0a0a0a] p-6 rounded-2xl w-full max-w-sm max-h-full overflow-y-auto text-gray-200 font-mono border border-[#333] shadow-2xl">
            <h2 className="text-lg font-bold mb-4 flex items-center justify-between text-white border-b border-[#333] pb-2">
              <div className="flex items-center gap-2">
                <Settings size={18}/> タップ優先度設定
              </div>
              <div className="flex bg-[#111] rounded p-0.5 border border-[#333]">
                <button
                  onClick={() => setActivePriorityIndex(1)}
                  className={`px-3 py-1 text-xs rounded font-bold transition-colors ${activePriorityIndex === 1 ? 'bg-emerald-900/40 text-emerald-400' : 'text-gray-500 hover:text-gray-300'}`}
                >
                  設定1
                </button>
                <button
                  onClick={() => setActivePriorityIndex(2)}
                  className={`px-3 py-1 text-xs rounded font-bold transition-colors ${activePriorityIndex === 2 ? 'bg-emerald-900/40 text-emerald-400' : 'text-gray-500 hover:text-gray-300'}`}
                >
                  設定2
                </button>
              </div>
            </h2>
            <p className="text-[10px] text-gray-400 mb-4">
              ドラッグ＆ドロップで優先度を入れ替えます（上が優先度高）。
            </p>
            
            <Reorder.Group 
              axis="y" 
              values={tapPriorities} 
              onReorder={setTapPriorities} 
              className="space-y-2 mb-6"
            >
              {tapPriorities.map((item) => (
                <Reorder.Item 
                  key={item.id} 
                  value={item}
                  className="flex items-center gap-3 bg-[#222] border border-[#333] rounded px-3 py-2 cursor-grab active:cursor-grabbing"
                >
                  <GripVertical size={16} className="text-gray-500" />
                  <span className="text-xs font-bold text-gray-300">{item.label}</span>
                </Reorder.Item>
              ))}
            </Reorder.Group>

            <button
              onClick={() => setShowPrioritySettings(false)}
              className="w-full py-3 bg-emerald-600 hover:bg-emerald-500 text-white rounded-lg font-bold transition-colors"
            >
              閉じる
            </button>
          </div>
        </div>
      )}

      {/* OCR Window Modal */}
      {showOCRWindow && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-black/80 backdrop-blur-sm p-4">
          <div className="bg-[#0a0a0a] p-6 rounded-2xl w-full max-h-full overflow-y-auto text-gray-200 font-mono border border-[#333] shadow-2xl custom-scrollbar">
            <h2 className="text-lg font-bold mb-6 flex items-center gap-2 text-white sticky top-0 bg-[#0a0a0a] pb-2 z-10 border-b border-[#333]">
              <ScanText size={18}/> 個別手動同期 (OCR)
            </h2>
            
            <div className="space-y-6">
              <div className="pb-4">
                <label className="block text-[10px] text-gray-500 mb-3">
                  ※ゲームの仕様上、キャラごとに画面を切り替えてタップしてください
                </label>

                {/* Expected Layout Guide */}
                <div className="mb-6 bg-[#111] border border-[#333] rounded-xl p-3">
                  <div className="text-[10px] text-emerald-400 font-bold mb-2 flex items-center gap-1">
                    <Scan size={12} /> 想定画面配置（キャラ詳細画面）
                  </div>
                  <div className="relative w-full h-40 bg-[#050505] border border-[#222] rounded-lg overflow-hidden flex">
                    {/* Left side: Character Model & Name */}
                    <div className="w-1/2 h-full relative border-r border-[#222]">
                      <div className="absolute top-2 left-2 border border-emerald-500/50 bg-emerald-900/20 text-emerald-400 text-[8px] px-1.5 py-0.5 rounded-full flex items-center gap-1">
                        名前 <span className="text-[6px] opacity-70">(Name)</span>
                      </div>
                      {/* Mock Character Silhouette */}
                      <div className="absolute bottom-0 left-1/2 -translate-x-1/2 w-14 h-24 bg-[#222] rounded-t-full flex items-center justify-center">
                        <div className="text-[#333] text-[10px]">Model</div>
                      </div>
                    </div>
                    {/* Right side: Stats Panel */}
                    <div className="w-1/2 h-full p-2 flex flex-col gap-1.5">
                      {/* Job Box */}
                      <div className="border border-emerald-500/50 bg-emerald-900/20 text-emerald-400 text-[8px] px-1.5 py-1 rounded w-full flex flex-col">
                        <span className="text-[6px] opacity-70">職業 (Job)</span>
                        <div className="h-1.5 bg-emerald-900/40 rounded w-1/2 mt-0.5"></div>
                      </div>
                      {/* Stats Box */}
                      <div className="border border-emerald-500/50 bg-emerald-900/20 text-emerald-400 text-[8px] px-1.5 py-1 rounded w-full flex-1 flex flex-col justify-between">
                        <div className="flex justify-between items-center">
                          <span>Lv</span>
                          <div className="h-1.5 bg-emerald-900/40 rounded w-4"></div>
                        </div>
                        <div className="flex justify-between items-center">
                          <span>経験値</span>
                          <div className="h-1.5 bg-emerald-900/40 rounded w-12"></div>
                        </div>
                        <div className="flex justify-between items-center">
                          <span>次のレベル</span>
                          <div className="h-1.5 bg-emerald-900/40 rounded w-10"></div>
                        </div>
                      </div>
                    </div>
                    
                    {/* Scanning Animation Overlay */}
                    {isScanning && scanningMemberId !== -1 && (
                      <div className="absolute inset-0 scan-line pointer-events-none" />
                    )}
                  </div>
                  <div className="text-[9px] text-gray-500 mt-2 text-center">
                    このレイアウトを基準にOCR読み取りを行います
                  </div>
                </div>

                <div className="grid grid-cols-2 gap-2">
                  {party.map(member => (
                    <button 
                      key={member.id}
                      onClick={() => handleOCRScan(member.id)}
                      disabled={isScanning}
                      className={`relative overflow-hidden p-3 rounded-xl border text-left transition-all ${
                        scanningMemberId === member.id 
                          ? 'bg-emerald-900/40 border-emerald-500' 
                          : 'bg-[#111] border-[#333] hover:border-[#555] disabled:opacity-50'
                      }`}
                    >
                      {scanningMemberId === member.id && (
                        <div className="absolute inset-0 scan-line opacity-30 pointer-events-none" />
                      )}
                      <div className="flex justify-between items-start mb-1">
                        <span className="text-[10px] text-emerald-400 font-bold">ID:{member.id}</span>
                        {scanningMemberId === member.id && <Scan size={12} className="text-emerald-400 animate-spin" />}
                      </div>
                      <div className="font-bold text-white text-sm truncate">{member.name}</div>
                      <div className="text-[10px] text-gray-400 truncate">{member.job}</div>
                      <div className="mt-1.5 text-xs font-bold text-emerald-400">Lv.{member.level}</div>
                    </button>
                  ))}
                </div>
              </div>
            </div>

            <button 
              onClick={() => setShowOCRWindow(false)}
              className="mt-8 w-full py-3 bg-[#111] border border-[#333] text-white rounded-xl hover:bg-[#222] transition-colors font-bold text-sm tracking-widest sticky bottom-0"
            >
              閉じる
            </button>
          </div>
        </div>
      )}
      </div>
    </div>
  );
}


