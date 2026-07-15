import React, { useState, useEffect, useRef } from 'react';
import { 
  Cloud, 
  RefreshCw, 
  LogIn, 
  LogOut, 
  Plus, 
  Calendar, 
  CheckCircle2, 
  Circle, 
  ChevronRight, 
  ChevronLeft, 
  Trash2, 
  ArrowLeftRight, 
  Settings, 
  Check, 
  AlertTriangle,
  Info,
  Sliders,
  Sparkles,
  Download,
  PanelLeftClose,
  PanelLeftOpen
} from 'lucide-react';

// ==========================================
// CONSTANTS & HELPERS
// ==========================================
const DAY_NAMES = ["週一", "週二", "週三", "週四", "週五", "週六", "週日"];

const DAY_TYPES = {
  "SPORT": { name: "運動日 🏃‍♂️", color: "bg-amber-500/10 text-amber-400 border-amber-500/30" },
  "NON_SPORT": { name: "非運動日 ☕", color: "bg-emerald-500/10 text-emerald-400 border-emerald-500/30" },
  "SATURDAY": { name: "週六 🧸", color: "bg-indigo-500/10 text-indigo-400 border-indigo-500/30" },
  "SUNDAY": { name: "週日 🧘", color: "bg-purple-500/10 text-purple-400 border-purple-500/30" }
};

const GAP_SEMANTICS = {
  "AMBER": { label: "零碎縫隙", color: "text-[#E5A93C] border-[#E5A93C]/30 bg-[#E5A93C]/5", labelBg: "bg-[#E5A93C]/10 text-[#E5A93C]", hex: "#E5A93C" },
  "GREEN": { label: "可放", color: "text-[#5FA777] border-[#5FA777]/30 bg-[#5FA777]/5", labelBg: "bg-[#5FA777]/10 text-[#5FA777]", hex: "#5FA777" },
  "PURPLE": { label: "睡眠保護", color: "text-[#B39DDB] border-[#B39DDB]/30 bg-[#B39DDB]/5", labelBg: "bg-[#B39DDB]/10 text-[#B39DDB]", hex: "#B39DDB" },
  "GREY_LOCKED": { label: "鎖定", color: "text-slate-500 border-slate-700/30 bg-slate-900/40 opacity-70", labelBg: "bg-slate-800 text-slate-500", hex: "#757575" },
  "GREY_TIGHT": { label: "行程緊湊", color: "text-[#A1887F] border-[#A1887F]/30 bg-[#A1887F]/5", labelBg: "bg-[#A1887F]/10 text-[#A1887F]", hex: "#A1887F" }
};

function getGapsForDayType(dayType, dayIndex) {
  switch (dayType) {
    case "SPORT":
      return [
        { id: `${dayIndex}_0`, timeRange: "07:10–08:00", name: "通勤後乾等", semantic: "AMBER" },
        { id: `${dayIndex}_1`, timeRange: "09:00–17:30", name: "上班", semantic: "GREY_LOCKED" },
        { id: `${dayIndex}_2`, timeRange: "17:30–20:00", name: "回家→團課前", semantic: "GREY_TIGHT", isTight: true },
        { id: `${dayIndex}_3`, timeRange: "20:00–21:00", name: "團課", semantic: "GREY_LOCKED" },
        { id: `${dayIndex}_4`, timeRange: "21:00–睡", name: "下課洗澡睡", semantic: "PURPLE" }
      ];
    case "NON_SPORT":
      return [
        { id: `${dayIndex}_0`, timeRange: "07:10–08:00", name: "乾等", semantic: "AMBER" },
        { id: `${dayIndex}_1`, timeRange: "09:00–17:30", name: "上班", semantic: "GREY_LOCKED" },
        { id: `${dayIndex}_2`, timeRange: "17:30–22:30", name: "晚上", semantic: "GREEN" },
        { id: `${dayIndex}_3`, timeRange: "22:30–睡", name: "早睡還債", semantic: "PURPLE" }
      ];
    case "SATURDAY":
      return [
        { id: `${dayIndex}_0`, timeRange: "上午", name: "可放", semantic: "GREEN" },
        { id: `${dayIndex}_1`, timeRange: "中午–下午", name: "可放", semantic: "GREEN" },
        { id: `${dayIndex}_2`, timeRange: "傍晚", name: "可放", semantic: "GREEN" },
        { id: `${dayIndex}_3`, timeRange: "晚上→週日", name: "必睡窗口", semantic: "PURPLE" }
      ];
    case "SUNDAY":
      return [
        { id: `${dayIndex}_0`, timeRange: "上午", name: "補眠緩衝", semantic: "GREEN" },
        { id: `${dayIndex}_1`, timeRange: "下午", name: "可放", semantic: "GREEN" },
        { id: `${dayIndex}_2`, timeRange: "晚上", name: "可放", semantic: "GREEN" }
      ];
    default:
      return [];
  }
}

// Get today's index: Monday is 0, Sunday is 6
function getTodayIndex() {
  const day = new Date().getDay(); // 0 (Sunday) to 6 (Saturday)
  return day === 0 ? 6 : day - 1;
}

// Get the date string for a given dayIndex (0..6) and weekOffset (yyyy-MM-dd)
function getFullDateStringForIndex(dayIndex, weekOffset = 0) {
  const today = new Date();
  const todayIndex = getTodayIndex();
  const diffDays = (dayIndex - todayIndex) + (weekOffset * 7);
  const target = new Date(today);
  target.setDate(today.getDate() + diffDays);
  
  const yyyy = target.getFullYear();
  const mm = String(target.getMonth() + 1).padStart(2, '0');
  const dd = String(target.getDate()).padStart(2, '0');
  return `${yyyy}-${mm}-${dd}`;
}

// Get short date M/d for display in column headers
function getShortDateStringForIndex(dayIndex, weekOffset = 0) {
  const today = new Date();
  const todayIndex = getTodayIndex();
  const diffDays = (dayIndex - todayIndex) + (weekOffset * 7);
  const target = new Date(today);
  target.setDate(today.getDate() + diffDays);
  
  return `${target.getMonth() + 1}/${target.getDate()}`;
}

// Get long date yyyy.MM.dd for active display
function getDateStringForIndex(dayIndex, weekOffset = 0) {
  const today = new Date();
  const todayIndex = getTodayIndex();
  const diffDays = (dayIndex - todayIndex) + (weekOffset * 7);
  const target = new Date(today);
  target.setDate(today.getDate() + diffDays);
  
  const yyyy = target.getFullYear();
  const mm = String(target.getMonth() + 1).padStart(2, '0');
  const dd = String(target.getDate()).padStart(2, '0');
  return `${yyyy}.${mm}.${dd}`;
}

// ==========================================
// CORE WEB COMPANION APP
// ==========================================
export default function App() {
  // --- Google OAuth Configs ---
  const [clientId, setClientId] = useState(() => localStorage.getItem('luodi_client_id') || '');
  const [googleUser, setGoogleUser] = useState(null);
  const [accessToken, setAccessToken] = useState(() => localStorage.getItem('luodi_access_token') || '');
  const [isSyncing, setIsSyncing] = useState(false);
  const [syncMessage, setSyncMessage] = useState('');
  const [syncConflict, setSyncConflict] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [weekOffset, setWeekOffset] = useState(0);
  const [isLeftPanelOpen, setIsLeftPanelOpen] = useState(() => {
    const local = localStorage.getItem('luodi_left_panel_open');
    return local !== null ? JSON.parse(local) : true;
  });

  // --- Core Application State ---
  const [thoughts, setThoughts] = useState(() => {
    const local = localStorage.getItem('luodi_thoughts');
    return local ? JSON.parse(local) : [];
  });
  
  const [dayConfigs, setDayConfigs] = useState(() => {
    const local = localStorage.getItem('luodi_day_configs');
    if (local) return JSON.parse(local);
    // Default weekly configurations matching Android app
    return [
      { dayIndex: 0, dayName: "週一", dayType: "NON_SPORT", updatedAt: Date.now() },
      { dayIndex: 1, dayName: "週二", dayType: "SPORT", updatedAt: Date.now() },
      { dayIndex: 2, dayName: "週三", dayType: "NON_SPORT", updatedAt: Date.now() },
      { dayIndex: 3, dayName: "週四", dayType: "SPORT", updatedAt: Date.now() },
      { dayIndex: 4, dayName: "週五", dayType: "NON_SPORT", updatedAt: Date.now() },
      { dayIndex: 5, dayName: "週六", dayType: "SATURDAY", updatedAt: Date.now() },
      { dayIndex: 6, dayName: "週日", dayType: "SUNDAY", updatedAt: Date.now() }
    ];
  });

  // --- New Thought Form State ---
  const [newTitle, setNewTitle] = useState('');
  const [newType, setNewType] = useState('REVIEW'); // REVIEW, FOCUS
  const [hasDeadline, setHasDeadline] = useState(false);
  const [dueDate, setDueDate] = useState('');

  // --- Interactive Dialogs/Menus ---
  const [editingDayIdx, setEditingDayIdx] = useState(null);
  const [warningMessage, setWarningMessage] = useState('');
  const [pendingPlacement, setPendingPlacement] = useState(null);

  // --- Auto-Save to LocalStorage ---
  useEffect(() => {
    localStorage.setItem('luodi_thoughts', JSON.stringify(thoughts));
  }, [thoughts]);

  useEffect(() => {
    localStorage.setItem('luodi_day_configs', JSON.stringify(dayConfigs));
  }, [dayConfigs]);

  useEffect(() => {
    localStorage.setItem('luodi_left_panel_open', JSON.stringify(isLeftPanelOpen));
  }, [isLeftPanelOpen]);

  // --- Save Google Credentials ---
  useEffect(() => {
    if (clientId) {
      localStorage.setItem('luodi_client_id', clientId);
    } else {
      localStorage.removeItem('luodi_client_id');
    }
  }, [clientId]);

  useEffect(() => {
    if (accessToken) {
      localStorage.setItem('luodi_access_token', accessToken);
      // Fetch user profile
      fetchUserProfile(accessToken);
    } else {
      localStorage.removeItem('luodi_access_token');
      setGoogleUser(null);
    }
  }, [accessToken]);

  // --- Fetch Google User Profile ---
  const fetchUserProfile = async (token) => {
    try {
      const res = await fetch('https://www.googleapis.com/oauth2/v3/userinfo', {
        headers: { Authorization: `Bearer ${token}` }
      });
      if (res.ok) {
        const data = await res.json();
        setGoogleUser(data);
      } else if (res.status === 401) {
        // Token expired
        setAccessToken('');
      }
    } catch (e) {
      console.error(e);
    }
  };

  // --- Initial/Background Sync on Load ---
  useEffect(() => {
    if (accessToken) {
      syncWithGoogleDrive(accessToken);
    }
  }, []);

  // ==========================================
  // GOOGLE DRIVE API SYNC LOGIC
  // ==========================================
  const handleGoogleSignIn = () => {
    if (!clientId) {
      setWarningMessage('請先在設定中輸入您的 Google OAuth 2.0 用戶端 ID 🔑');
      setShowSettings(true);
      return;
    }

    try {
      const client = google.accounts.oauth2.initTokenClient({
        client_id: clientId,
        scope: 'https://www.googleapis.com/auth/drive.appdata',
        callback: (response) => {
          if (response.access_token) {
            setAccessToken(response.access_token);
            setSyncMessage('Google 登入成功！');
            syncWithGoogleDrive(response.access_token);
          }
        },
      });
      client.requestAccessToken();
    } catch (err) {
      console.error(err);
      setWarningMessage('Google 登入初始化失敗，請確認您的 Client ID 是否正確。');
    }
  };

  const handleSignOut = () => {
    setAccessToken('');
    setGoogleUser(null);
    setSyncConflict(false);
    setSyncMessage('已登出 Google 帳號');
  };

  // Dual-sync merge routine (exact matching Kotlin logic)
  const syncWithGoogleDrive = async (token) => {
    if (!token) return;
    setIsSyncing(true);
    setSyncMessage('正在同步雲端資料...');

    try {
      // 1. Find the sync file in Google Drive AppData folder
      const listUrl = 'https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name%3D%27luodi_sync_backup.json%27&orderBy=modifiedTime%20desc&fields=files(id,name)';
      const listRes = await fetch(listUrl, {
        headers: { Authorization: `Bearer ${token}` }
      });

      if (!listRes.ok) throw new Error('無法查詢雲端檔案');
      const listData = await listRes.json();
      const files = listData.files || [];

      let cloudData = null;
      let fileId = null;

      if (files.length > 0) {
        fileId = files[0].id;
        // 2. Download cloud file
        const downRes = await fetch(`https://www.googleapis.com/drive/v3/files/${fileId}?alt=media`, {
          headers: { Authorization: `Bearer ${token}` }
        });
        if (downRes.ok) {
          cloudData = await downRes.json();
        }
      }

      // Local state snapshot
      const currentThoughts = [...thoughts];
      const currentDayConfigs = [...dayConfigs];

      if (!cloudData) {
        // First-time sync: Create cloud file with local data
        const payload = {
          thoughts: currentThoughts,
          dayConfigs: currentDayConfigs,
          device: "Web Companion",
          lastSyncTime: Date.now()
        };

        const metadata = {
          name: 'luodi_sync_backup.json',
          parents: ['appDataFolder']
        };

        const boundary = 'foo_bar_boundary';
        const multipartBody = 
          `\r\n--${boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n${JSON.stringify(metadata)}` +
          `\r\n--${boundary}\r\nContent-Type: application/json\r\n\r\n${JSON.stringify(payload)}` +
          `\r\n--${boundary}--`;

        const uploadUrl = 'https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart';
        const uploadRes = await fetch(uploadUrl, {
          method: 'POST',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': `multipart/related; boundary=${boundary}`
          },
          body: multipartBody
        });

        if (uploadRes.ok) {
          setSyncMessage('雲端初始化同步成功 ✨');
        } else {
          throw new Error('無法創建雲端備份');
        }
      } else {
        // Merge routine (identical rules to Kotlin implementation)
        const cloudThoughts = cloudData.thoughts || [];
        const cloudConfigs = cloudData.dayConfigs || [];

        // Detect if there's any mismatch/conflict
        let isMismatched = false;
        if (cloudThoughts.length !== currentThoughts.length) {
          isMismatched = true;
        } else {
          const localMap = new Map(currentThoughts.map(t => [t.createdAt || t.id, t]));
          for (const ct of cloudThoughts) {
            const key = ct.createdAt || ct.id;
            const local = localMap.get(key);
            if (
              !local || 
              local.title !== ct.title || 
              local.status !== ct.status || 
              local.placedDayIndex !== ct.placedDayIndex || 
              local.placedSlotId !== ct.placedSlotId || 
              local.type !== ct.type ||
              local.placedDate !== ct.placedDate
            ) {
              isMismatched = true;
              break;
            }
          }
        }
        setSyncConflict(isMismatched);

        // Thoughts Merge
        const mergedThoughtsMap = new Map();
        // Add all local
        currentThoughts.forEach(t => {
          const key = t.createdAt || t.id;
          mergedThoughtsMap.set(key, t);
        });
        // Merge cloud using updatedAt
        cloudThoughts.forEach(ct => {
          const key = ct.createdAt || ct.id;
          const local = mergedThoughtsMap.get(key);
          if (!local || ct.updatedAt > (local.updatedAt || 0)) {
            const mergedItem = { ...ct };
            if (!mergedItem.id) {
              mergedItem.id = ct.createdAt || Math.floor(Math.random() * 10000000);
            }
            mergedThoughtsMap.set(key, mergedItem);
          }
        });
        const mergedThoughts = Array.from(mergedThoughtsMap.values());

        // Day Configs Merge
        const mergedConfigsMap = new Map();
        currentDayConfigs.forEach(c => mergedConfigsMap.set(c.dayIndex, c));
        cloudConfigs.forEach(cc => {
          const local = mergedConfigsMap.get(cc.dayIndex);
          if (!local || !cc.updatedAt || cc.updatedAt > (local.updatedAt || 0)) {
            mergedConfigsMap.set(cc.dayIndex, {
              ...cc,
              updatedAt: cc.updatedAt || Date.now()
            });
          }
        });
        const mergedConfigs = Array.from(mergedConfigsMap.values()).sort((a,b) => a.dayIndex - b.dayIndex);

        // Update local state
        setThoughts(mergedThoughts);
        setDayConfigs(mergedConfigs);

        // Upload merged state back to cloud
        const payload = {
          thoughts: mergedThoughts,
          dayConfigs: mergedConfigs,
          device: "Web Companion",
          lastSyncTime: Date.now()
        };

        const uploadUrl = `https://www.googleapis.com/upload/drive/v3/files/${fileId}?uploadType=media`;
        const uploadRes = await fetch(uploadUrl, {
          method: 'PATCH',
          headers: {
            Authorization: `Bearer ${token}`,
            'Content-Type': 'application/json'
          },
          body: JSON.stringify(payload)
        });

        if (uploadRes.ok) {
          if (isMismatched) {
            setSyncMessage('偵測到版本差異，已自動雙向合併 ✨');
          } else {
            setSyncMessage('雲端雙向同步成功 ✨');
          }
        } else {
          throw new Error('無法更新雲端檔案');
        }
      }
    } catch (err) {
      console.error(err);
      setSyncMessage(`同步失敗: ${err.message}`);
    } finally {
      setIsSyncing(false);
      // Clear status message after 4s
      setTimeout(() => setSyncMessage(''), 4000);
    }
  };

  // Force overwrite local Web Companion state with pristine cloud data from Google Drive
  const forceImportFromGoogleDrive = async (token) => {
    if (!token) return;
    setIsSyncing(true);
    setSyncMessage('正在從雲端強制匯入資料...');

    try {
      // 1. Find the sync file in Google Drive AppData folder
      const listUrl = 'https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name%3D%27luodi_sync_backup.json%27&orderBy=modifiedTime%20desc&fields=files(id,name)';
      const listRes = await fetch(listUrl, {
        headers: { Authorization: `Bearer ${token}` }
      });

      if (!listRes.ok) throw new Error('無法查詢雲端檔案');
      const listData = await listRes.json();
      const files = listData.files || [];

      if (files.length === 0) {
        setSyncMessage('雲端沒有備份資料可供匯入 ⚠️');
        setIsSyncing(false);
        return;
      }

      const fileId = files[0].id;
      // 2. Download cloud file
      const downRes = await fetch(`https://www.googleapis.com/drive/v3/files/${fileId}?alt=media`, {
        headers: { Authorization: `Bearer ${token}` }
      });
      if (!downRes.ok) throw new Error('下載雲端檔案失敗');
      const cloudData = await downRes.json();

      if (!cloudData) {
        throw new Error('雲端檔案內容為空');
      }

      const cloudThoughts = cloudData.thoughts || [];
      const cloudConfigs = cloudData.dayConfigs || [];

      // Force format / check items
      const formattedThoughts = cloudThoughts.map(ct => {
        const mergedItem = { ...ct };
        if (!mergedItem.id) {
          mergedItem.id = ct.createdAt || Math.floor(Math.random() * 10000000);
        }
        return mergedItem;
      });

      const formattedConfigs = cloudConfigs.map(cc => ({
        ...cc,
        updatedAt: cc.updatedAt || Date.now()
      })).sort((a,b) => a.dayIndex - b.dayIndex);

      // Force overwrite local state
      setThoughts(formattedThoughts);
      setDayConfigs(formattedConfigs);

      // Reset conflict status since we are now perfectly aligned with cloud
      setSyncConflict(false);

      setSyncMessage('已強制從裝置/雲端備份覆蓋匯入！ ✨');
    } catch (err) {
      console.error(err);
      setSyncMessage(`強制匯入失敗: ${err.message} ❌`);
    } finally {
      setIsSyncing(false);
      // Clear message after 5 seconds
      setTimeout(() => setSyncMessage(''), 5000);
    }
  };

  // Helper trigger to save state to cloud after a local write
  const triggerPushToCloud = async (updatedThoughts = thoughts, updatedConfigs = dayConfigs) => {
    if (!accessToken) return;
    try {
      const listUrl = 'https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=name%3D%27luodi_sync_backup.json%27&orderBy=modifiedTime%20desc&fields=files(id)';
      const listRes = await fetch(listUrl, {
        headers: { Authorization: `Bearer ${accessToken}` }
      });
      if (!listRes.ok) return;
      const listData = await listRes.json();
      const files = listData.files || [];
      if (files.length === 0) return;

      const fileId = files[0].id;
      const payload = {
        thoughts: updatedThoughts,
        dayConfigs: updatedConfigs,
        device: "Web Companion",
        lastSyncTime: Date.now()
      };

      await fetch(`https://www.googleapis.com/upload/drive/v3/files/${fileId}?uploadType=media`, {
        method: 'PATCH',
        headers: {
          Authorization: `Bearer ${accessToken}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
      });
    } catch (e) {
      console.error('Background upload failed', e);
    }
  };

  // ==========================================
  // CORE BUSINESS MUTATIONS
  // ==========================================
  const addThought = (e) => {
    e.preventDefault();
    if (!newTitle.trim()) return;

    const newThought = {
      id: Math.floor(Math.random() * 10000000),
      title: newTitle.trim(),
      type: newType,
      isDeadline: hasDeadline,
      dueDate: hasDeadline ? dueDate : null,
      status: "PENDING",
      placedDayIndex: null,
      placedSlotId: null,
      placedDate: null,
      createdAt: Date.now(),
      updatedAt: Date.now(),
      customOrder: thoughts.length
    };

    const updated = [newThought, ...thoughts];
    setThoughts(updated);
    setNewTitle('');
    setHasDeadline(false);
    setDueDate('');
    triggerPushToCloud(updated);
  };

  const removeThought = (id) => {
    const updated = thoughts.filter(t => t.id !== id);
    setThoughts(updated);
    triggerPushToCloud(updated);
  };

  const toggleComplete = (thought) => {
    const isCompleted = thought.status === "COMPLETED";
    const updated = thoughts.map(t => {
      if (t.id === thought.id) {
        return {
          ...t,
          status: isCompleted ? "PLACED" : "COMPLETED",
          completedAt: isCompleted ? null : Date.now(),
          updatedAt: Date.now()
        };
      }
      return t;
    });
    setThoughts(updated);
    triggerPushToCloud(updated);
  };

  const placeThoughtDirect = (thoughtId, dayIndex, slotId) => {
    const updated = thoughts.map(t => {
      if (t.id === thoughtId) {
        return {
          ...t,
          status: "PLACED",
          placedDayIndex: dayIndex,
          placedSlotId: slotId,
          placedDate: getFullDateStringForIndex(dayIndex, weekOffset),
          updatedAt: Date.now()
        };
      }
      return t;
    });

    setThoughts(updated);
    triggerPushToCloud(updated);
  };

  const placeThought = (thoughtId, dayIndex, slotId) => {
    const config = dayConfigs.find(c => c.dayIndex === dayIndex);
    if (!config) return;
    const gaps = getGapsForDayType(config.dayType, dayIndex);
    const gap = gaps.find(g => g.id === slotId);
    if (!gap) return;

    const thought = thoughts.find(t => t.id === thoughtId);
    if (!thought) return;

    // Check Locked
    const isLocked = gap.semantic === "GREY_LOCKED";
    if (isLocked) {
      setWarningMessage("此時間段已被鎖定，無法安放任務喔。");
      return;
    }

    // Check Type compatibility
    const isTypeCompatible = thought.type === "REVIEW"
      ? (gap.semantic === "AMBER" || gap.semantic === "GREEN" || gap.semantic === "PURPLE" || gap.isTight)
      : (gap.semantic === "GREEN" || gap.semantic === "PURPLE" || gap.isTight);

    if (!isTypeCompatible) {
      setWarningMessage("這個縫隙不適合此類型的念頭（零碎型只收琥珀與綠色縫隙，專注型只收綠色縫隙）");
      return;
    }

    // Check Warnings
    if (gap.semantic === "PURPLE") {
      setPendingPlacement({
        thoughtId,
        dayIndex,
        slotId,
        message: "這會吃到你的睡眠，換一格／還是要放？"
      });
    } else if (gap.isTight) {
      setPendingPlacement({
        thoughtId,
        dayIndex,
        slotId,
        message: "這個縫隙行程非常緊湊，換一格／還是要放？"
      });
    } else {
      placeThoughtDirect(thoughtId, dayIndex, slotId);
    }
  };

  const returnToPending = (thoughtId) => {
    const updated = thoughts.map(t => {
      if (t.id === thoughtId) {
        return {
          ...t,
          status: "PENDING",
          placedDayIndex: null,
          placedSlotId: null,
          placedDate: null,
          updatedAt: Date.now()
        };
      }
      return t;
    });
    setThoughts(updated);
    triggerPushToCloud(updated);
  };

  const changeDayType = (dayIndex, newType) => {
    const updated = dayConfigs.map(c => {
      if (c.dayIndex === dayIndex) {
        return { ...c, dayType: newType, updatedAt: Date.now() };
      }
      return c;
    });
    setDayConfigs(updated);
    setEditingDayIdx(null);
    triggerPushToCloud(thoughts, updated);
  };

  // ==========================================
  // BRAIN LOAD LOGIC
  // ==========================================
  const pendingThoughts = thoughts.filter(t => t.status === "PENDING");
  const pendingCount = pendingThoughts.length;

  const { brainLoadText, brainLoadColor } = (() => {
    if (pendingCount === 0) {
      return { brainLoadText: "大腦已清空，安然入眠 🧘", brainLoadColor: "text-green-400 border-green-500/20 bg-green-500/5" };
    } else if (pendingCount >= 1 && pendingCount <= 5) {
      return { brainLoadText: "大腦微載，正是整理思緒的好時機 ✨", brainLoadColor: "text-cosmic-cyan border-cosmic-cyan/20 bg-cosmic-cyan/5" };
    } else if (pendingCount >= 6 && pendingCount <= 10) {
      return { brainLoadText: "大腦中載，將念頭安放到時間縫隙吧 🧠", brainLoadColor: "text-cosmic-gold border-cosmic-gold/20 bg-cosmic-gold/5" };
    } else {
      return { brainLoadText: "大腦超載！深呼吸，把它們排入課表吧 🌊", brainLoadColor: "text-cosmic-rose border-cosmic-rose/20 bg-cosmic-rose/5" };
    }
  })();

  // ==========================================
  // PRESENTATION RENDERING HELPERS
  // ==========================================
  const renderPendingItem = (thought) => {
    return (
      <div key={thought.id} className="p-3 bg-cosmic-700/60 border border-cosmic-600/30 rounded-xl hover:border-cosmic-cyan/40 transition-all flex items-start justify-between gap-3 group">
        <div className="flex-1">
          <div className="flex items-center gap-1.5 flex-wrap">
            <span className={`px-1.5 py-0.5 rounded text-xs font-bold ${thought.type === 'FOCUS' ? 'bg-cosmic-rose/10 text-cosmic-rose' : 'bg-cosmic-cyan/10 text-cosmic-cyan'}`}>
              {thought.type === 'FOCUS' ? '專注 ⚡' : '深思 💭'}
            </span>
            {thought.isDeadline && thought.dueDate && (
              <span className="bg-cosmic-gold/10 text-cosmic-gold border border-cosmic-gold/20 px-1.5 py-0.5 rounded text-xs flex items-center gap-0.5">
                <Calendar className="w-3 h-3" />
                {thought.dueDate}
              </span>
            )}
          </div>
          <p className="text-sm font-medium mt-1 text-slate-200 line-clamp-2">{thought.title}</p>
        </div>

        <div className="flex items-center gap-1 opacity-80 group-hover:opacity-100 transition-opacity">
          {/* Dispatch dropdown simulation */}
          <select 
            onChange={(e) => {
              if (e.target.value) {
                const [dayIdx, slotId] = e.target.value.split(':');
                placeThought(thought.id, parseInt(dayIdx), slotId);
                e.target.value = '';
              }
            }}
            className="text-xs bg-cosmic-800 border border-cosmic-600 rounded px-1.5 py-1 text-slate-300 focus:outline-none focus:border-cosmic-cyan"
            defaultValue=""
          >
            <option value="" disabled>排程到...</option>
            {dayConfigs.map((config) => {
              const gaps = getGapsForDayType(config.dayType, config.dayIndex);
              return (
                <optgroup key={config.dayIndex} label={`${config.dayName} (${getShortDateStringForIndex(config.dayIndex, weekOffset)})`}>
                  {gaps.map(gap => {
                    if (gap.semantic === "GREY_LOCKED") return null;
                    return (
                      <option key={gap.id} value={`${config.dayIndex}:${gap.id}`}>
                        {gap.timeRange} - {gap.name}
                      </option>
                    );
                  })}
                </optgroup>
              );
            })}
          </select>

          <button 
            onClick={() => removeThought(thought.id)}
            className="p-1 text-slate-500 hover:text-cosmic-rose hover:bg-cosmic-rose/5 rounded transition-all"
            title="刪除"
          >
            <Trash2 className="w-3.5 h-3.5" />
          </button>
        </div>
      </div>
    );
  };

  return (
    <div className="min-h-screen bg-cosmic-900 text-slate-100 flex flex-col antialiased">
      {/* ==========================================
          HEADER SECTION
          ========================================== */}
      <header className="border-b border-cosmic-800/80 bg-cosmic-900/90 backdrop-blur sticky top-0 z-40 px-4 md:px-8 py-4 flex flex-col md:flex-row items-center justify-between gap-4">
        <div className="flex items-center gap-3">
          <div className="w-10 h-10 rounded-xl bg-gradient-to-tr from-cosmic-cyan to-indigo-500 flex items-center justify-center shadow-lg shadow-cosmic-cyan/10">
            <Sparkles className="w-5 h-5 text-cosmic-900" />
          </div>
          <div>
            <h1 className="text-xl font-extrabold tracking-tight bg-gradient-to-r from-white to-slate-400 bg-clip-text text-transparent">落念 Luodi</h1>
            <p className="text-xs text-slate-400 font-medium">桌上型電腦網頁 Companion App</p>
          </div>
        </div>

        {/* Sync Status Info Bar */}
        <div className="flex items-center gap-3 flex-wrap">
          {syncMessage && (
            <div className="text-xs bg-cosmic-700/80 border border-cosmic-600/50 px-3 py-1.5 rounded-full flex items-center gap-1.5 animate-pulse text-cosmic-cyan">
              <RefreshCw className="w-3 h-3 animate-spin" />
              <span>{syncMessage}</span>
            </div>
          )}

          {googleUser ? (
            <div className="flex items-center gap-3">
              <div className="flex items-center gap-3 bg-cosmic-800/90 border border-cosmic-700 px-3.5 py-1.5 rounded-full">
                {googleUser.picture ? (
                  <img src={googleUser.picture} alt="Avatar" className="w-5 h-5 rounded-full" />
                ) : (
                  <div className="w-5 h-5 rounded-full bg-cosmic-cyan flex items-center justify-center text-xs text-cosmic-900 font-bold">
                    G
                  </div>
                )}
                <span className="text-sm font-semibold text-slate-200">{googleUser.name}</span>
                <button 
                  onClick={() => syncWithGoogleDrive(accessToken)} 
                  disabled={isSyncing}
                  className="p-1 text-slate-400 hover:text-cosmic-cyan rounded transition-all"
                  title="立即手動雙向同步"
                >
                  <RefreshCw className={`w-3.5 h-3.5 ${isSyncing ? 'animate-spin text-cosmic-cyan' : ''}`} />
                </button>
                <button 
                  onClick={handleSignOut}
                  className="p-1 text-slate-400 hover:text-cosmic-rose rounded transition-all"
                  title="登出"
                >
                  <LogOut className="w-3.5 h-3.5" />
                </button>
              </div>

              <button 
                onClick={() => {
                  if (window.confirm("確定要強制使用「手機端/雲端」的備份資料覆蓋目前網頁端的儲存數據嗎？\n\n⚠️ 注意：這將會完全清除此網頁端現有的所有念頭與日型配置！")) {
                    forceImportFromGoogleDrive(accessToken);
                  }
                }}
                disabled={isSyncing}
                className={`flex items-center gap-1.5 text-sm font-bold px-4 py-2 rounded-full border transition-all ${
                  syncConflict 
                    ? 'bg-amber-500/20 border-amber-500 text-amber-300 hover:bg-amber-500/30 animate-pulse' 
                    : 'bg-cosmic-800/80 border-cosmic-700 text-slate-300 hover:bg-cosmic-700 hover:text-white'
                }`}
                title="當手機與網頁資料有衝突時，強制用手機資料覆蓋此網頁"
              >
                <Download className="w-3.5 h-3.5" />
                <span>從裝置強制匯入</span>
              </button>
            </div>
          ) : (
            <button
              onClick={handleGoogleSignIn}
              className="flex items-center gap-1.5 bg-cosmic-cyan text-cosmic-900 font-bold text-sm px-4 py-2 rounded-full hover:bg-opacity-90 shadow-lg shadow-cosmic-cyan/10 transition-all"
            >
              <Cloud className="w-4 h-4" />
              <span>登入 Google 帳號並同步</span>
            </button>
          )}

          <button 
            onClick={() => setShowSettings(!showSettings)}
            className="p-2 bg-cosmic-800 border border-cosmic-700 text-slate-300 hover:text-white rounded-full transition-all"
            title="設定 Google Client ID"
          >
            <Settings className="w-4 h-4" />
          </button>
        </div>
      </header>

      {/* ==========================================
          MAIN LAYOUT CONTAINER
          ========================================== */}
      <main className="flex-1 max-w-7xl w-full mx-auto px-4 md:px-8 py-6 grid grid-cols-1 lg:grid-cols-12 gap-6 items-start">
        
        {/* ==========================================
            LEFT PANEL: INPUTS & PENDING LIST
            ========================================== */}
        {isLeftPanelOpen && (
          <section className="lg:col-span-4 flex flex-col gap-5">
            {/* Collapse Left Panel Button */}
            <button
              onClick={() => setIsLeftPanelOpen(false)}
              className="hidden lg:flex items-center justify-between w-full bg-cosmic-800/80 hover:bg-cosmic-700/80 border border-cosmic-700/60 hover:border-cosmic-500/80 px-4 py-3 rounded-2xl text-xs font-extrabold text-slate-300 hover:text-white transition-all shadow-md cursor-pointer group"
              title="收合左側面板"
            >
              <div className="flex items-center gap-2">
                <PanelLeftClose className="w-4 h-4 text-cosmic-rose group-hover:scale-105 transition-transform" />
                <span>收合左側 (大腦狀態/新念頭)</span>
              </div>
              <span className="text-[10px] text-slate-500 font-normal">Collapse</span>
            </button>

            {/* Brain Load Badge */}
          <div className={`p-4 border rounded-2xl flex items-start gap-3 transition-all ${brainLoadColor}`}>
            <Info className="w-5 h-5 mt-0.5 flex-shrink-0" />
            <div>
              <h3 className="text-base font-bold">目前大腦狀態</h3>
              <p className="text-sm font-medium mt-1 leading-relaxed">{brainLoadText}</p>
              <div className="mt-2.5 flex items-center gap-1">
                <span className="text-base font-extrabold">{pendingCount}</span>
                <span className="text-xs font-semibold text-slate-400">個念頭待排</span>
              </div>
            </div>
          </div>

          {/* New Thought Form */}
          <div className="bg-cosmic-800/90 border border-cosmic-700/80 rounded-2xl p-5 shadow-xl">
            <h2 className="text-base font-bold text-white mb-4 flex items-center gap-2">
              <Plus className="w-4 h-4 text-cosmic-cyan" />
              記錄新的念頭
            </h2>
            <form onSubmit={addThought} className="space-y-4">
              <div>
                <input 
                  type="text" 
                  value={newTitle}
                  onChange={(e) => setNewTitle(e.target.value)}
                  placeholder="輸入您的思緒或念頭..."
                  maxLength={50}
                  className="w-full bg-cosmic-700/60 border border-cosmic-600/50 rounded-xl px-4 py-3 text-sm text-white placeholder-slate-400 focus:outline-none focus:border-cosmic-cyan focus:ring-1 focus:ring-cosmic-cyan transition-all"
                  required
                />
              </div>

              {/* Tag selector */}
              <div className="flex gap-2">
                <button
                  type="button"
                  onClick={() => setNewType('REVIEW')}
                  className={`flex-1 py-2 text-sm font-bold rounded-lg border transition-all ${newType === 'REVIEW' ? 'bg-cosmic-cyan/10 border-cosmic-cyan text-cosmic-cyan' : 'bg-transparent border-cosmic-700 text-slate-400 hover:text-slate-200'}`}
                >
                  深思 💭
                </button>
                <button
                  type="button"
                  onClick={() => setNewType('FOCUS')}
                  className={`flex-1 py-2 text-sm font-bold rounded-lg border transition-all ${newType === 'FOCUS' ? 'bg-cosmic-rose/10 border-cosmic-rose text-cosmic-rose' : 'bg-transparent border-cosmic-700 text-slate-400 hover:text-slate-200'}`}
                >
                  專注 ⚡
                </button>
              </div>

              {/* Deadline Toggle */}
              <div className="bg-cosmic-700/30 border border-cosmic-700/40 p-3 rounded-xl space-y-3">
                <label className="flex items-center justify-between cursor-pointer">
                  <span className="text-sm font-semibold text-slate-300">有截止日期嗎？</span>
                  <input 
                    type="checkbox"
                    checked={hasDeadline}
                    onChange={(e) => setHasDeadline(e.target.checked)}
                    className="w-4 h-4 accent-cosmic-cyan"
                  />
                </label>
                {hasDeadline && (
                  <input 
                    type="date"
                    value={dueDate}
                    onChange={(e) => setDueDate(e.target.value)}
                    className="w-full bg-cosmic-800 border border-cosmic-600/50 rounded-lg px-3 py-1.5 text-sm text-slate-200 focus:outline-none focus:border-cosmic-cyan"
                    required
                  />
                )}
              </div>

              <button
                type="submit"
                className="w-full bg-gradient-to-r from-cosmic-cyan to-teal-500 text-cosmic-900 font-extrabold text-sm py-3 rounded-xl hover:opacity-95 shadow-lg shadow-cosmic-cyan/10 transition-all flex items-center justify-center gap-1.5"
              >
                <Plus className="w-4 h-4" />
                <span>投放到腦海 (待排區)</span>
              </button>
            </form>
          </div>

          {/* Pending thoughts list (Mind Area) */}
          <div className="bg-cosmic-800/90 border border-cosmic-700/80 rounded-2xl p-5 shadow-xl flex-1 flex flex-col max-h-[420px] lg:max-h-[600px]">
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-base font-extrabold text-white flex items-center gap-2">
                <Sliders className="w-4 h-4 text-cosmic-cyan" />
                腦海中的念頭待排區
              </h2>
              <span className="text-xs font-bold bg-cosmic-700 text-slate-300 px-2 py-0.5 rounded-full">
                {pendingCount} 個
              </span>
            </div>

            <div className="flex-1 overflow-y-auto pr-1 space-y-2.5">
              {pendingThoughts.length === 0 ? (
                <div className="h-40 flex flex-col items-center justify-center text-slate-500 text-center p-4">
                  <CheckCircle2 className="w-8 h-8 text-cosmic-cyan/20 mb-2" />
                  <p className="text-sm font-semibold">恭喜！目前大腦無遺留待辦</p>
                  <p className="text-xs mt-1">安心休息，或是記錄新思緒 ✨</p>
                </div>
              ) : (
                pendingThoughts.map(renderPendingItem)
              )}
            </div>
          </div>
        </section>
        )}

        {/* ==========================================
            RIGHT PANEL: WEEKLY TIMELINE PLANNER
            ========================================== */}
        <section className={`${isLeftPanelOpen ? 'lg:col-span-8' : 'lg:col-span-12'} bg-cosmic-800/90 border border-cosmic-700/80 rounded-2xl p-6 shadow-xl flex flex-col gap-6 transition-all duration-300`}>
          <div className="flex items-center justify-between flex-wrap gap-4 border-b border-cosmic-700/50 pb-4">
            <div className="flex items-center gap-4 flex-wrap">
              <div>
                <h2 className="text-base font-extrabold text-white flex items-center gap-2">
                  <Calendar className="w-4 h-4 text-cosmic-cyan" />
                  每週時間配置表
                </h2>
                <p className="text-xs text-slate-400 mt-0.5">點擊各日標籤可調整「日型」與大腦負荷限額 ⚡</p>
              </div>

              {/* Panel Toggle Button (Only visible when Left Panel is closed to expand it) */}
              {!isLeftPanelOpen && (
                <button
                  onClick={() => setIsLeftPanelOpen(true)}
                  className="hidden lg:flex items-center gap-1.5 bg-cosmic-700/60 hover:bg-cosmic-600/80 border border-cosmic-600/50 hover:border-cosmic-500 px-3 py-1.5 rounded-xl text-xs font-extrabold text-slate-300 hover:text-white transition-all shadow-sm cursor-pointer"
                  title="展開左側面板"
                >
                  <PanelLeftOpen className="w-4 h-4 text-cosmic-cyan animate-pulse" />
                  <span>展開左側 (大腦狀態/新念頭)</span>
                </button>
              )}

              {/* Week navigation control */}
              <div className="flex items-center gap-1.5 bg-cosmic-700/50 border border-cosmic-700/70 px-2.5 py-1 rounded-full shadow-inner">
                <button 
                  onClick={() => setWeekOffset(prev => prev - 1)}
                  className="p-1 hover:text-cosmic-cyan text-slate-400 hover:bg-cosmic-600/30 rounded-full transition-all"
                  title="上一週"
                >
                  <ChevronLeft className="w-4 h-4" />
                </button>
                
                <span className="text-sm font-bold text-slate-200 min-w-[55px] text-center px-1">
                  {weekOffset === 0 ? "本週" :
                   weekOffset === 1 ? "下週" :
                   weekOffset === -1 ? "上週" :
                   weekOffset < 0 ? `前 ${-weekOffset} 週` : `後 ${weekOffset} 週`}
                </span>

                <button 
                  onClick={() => setWeekOffset(prev => prev + 1)}
                  className="p-1 hover:text-cosmic-cyan text-slate-400 hover:bg-cosmic-600/30 rounded-full transition-all"
                  title="下一週"
                >
                  <ChevronRight className="w-4 h-4" />
                </button>

                {weekOffset !== 0 && (
                  <button
                    onClick={() => setWeekOffset(0)}
                    className="text-xs font-extrabold bg-cosmic-cyan/15 hover:bg-cosmic-cyan/25 text-cosmic-cyan border border-cosmic-cyan/20 px-2 py-0.5 rounded-md transition-all ml-1.5"
                  >
                    返回本週
                  </button>
                )}
              </div>
            </div>

            {/* Completion rate visual banner */}
            <div className="flex items-center gap-2.5">
              <span className="text-xs font-bold text-slate-300">
                {weekOffset === 0 ? "本週" :
                 weekOffset === 1 ? "下週" :
                 weekOffset === -1 ? "上週" :
                 weekOffset < 0 ? `前 ${-weekOffset} 週` : `後 ${weekOffset} 週`}完成率
              </span>
              <div className="w-24 bg-cosmic-700 h-2 rounded-full overflow-hidden">
                {(() => {
                  const weekThoughts = thoughts.filter(t => {
                    const targetDate = getFullDateStringForIndex(t.placedDayIndex, weekOffset);
                    return t.placedDayIndex !== null && (t.placedDate === targetDate || (!t.placedDate && weekOffset === 0));
                  });
                  const completed = weekThoughts.filter(t => t.status === "COMPLETED").length;
                  const pct = weekThoughts.length > 0 ? Math.round((completed / weekThoughts.length) * 100) : 0;
                  return (
                    <div 
                      className="bg-gradient-to-r from-cosmic-cyan to-teal-500 h-full transition-all duration-500"
                      style={{ width: `${pct}%` }}
                    />
                  );
                })()}
              </div>
            </div>
          </div>

          {/* 7-Day Timeline Flow */}
          <div className="space-y-6">
            {dayConfigs.map((config) => {
              const dayTypeInfo = DAY_TYPES[config.dayType || "NON_SPORT"];
              const targetDate = getFullDateStringForIndex(config.dayIndex, weekOffset);
              const dayThoughts = thoughts.filter(t => 
                t.placedDayIndex === config.dayIndex && 
                (t.placedDate === targetDate || (!t.placedDate && weekOffset === 0))
              );
              const gaps = getGapsForDayType(config.dayType || "NON_SPORT", config.dayIndex);
              
              // Count status
              const completedCount = dayThoughts.filter(t => t.status === "COMPLETED").length;
              const totalPlacedCount = dayThoughts.filter(t => t.status !== "PENDING").length;
              const isToday = config.dayIndex === getTodayIndex() && weekOffset === 0;

              return (
                <div 
                  key={config.dayIndex} 
                  className={`bg-cosmic-700/25 border rounded-2xl p-6 space-y-4 hover:border-cosmic-600/60 transition-colors ${
                    isToday ? 'border-cosmic-cyan/40 bg-cosmic-cyan/[0.02]' : 'border-cosmic-700/45'
                  }`}
                >
                  {/* Day Info Subheader */}
                  <div className="flex items-center justify-between flex-wrap gap-2">
                    <div className="flex items-center gap-3">
                      <h3 className="text-base font-bold text-slate-100">
                        {config.dayName}
                        <span className="text-xs font-normal text-slate-400 ml-1.5">
                          ({getShortDateStringForIndex(config.dayIndex, weekOffset)})
                        </span>
                      </h3>
                      {isToday && (
                        <span className="bg-cosmic-cyan/20 text-cosmic-cyan border border-cosmic-cyan/20 text-xs font-extrabold px-2 py-0.5 rounded-md shadow-sm">
                          今天
                        </span>
                      )}
                      <button
                        onClick={() => setEditingDayIdx(config.dayIndex)}
                        className={`text-xs font-extrabold border px-2.5 py-1 rounded-full transition-all hover:bg-white/5 ${dayTypeInfo.color}`}
                      >
                        {dayTypeInfo.name}
                      </button>
                    </div>

                    {/* Progress tracker */}
                    {totalPlacedCount > 0 && (
                      <div className="flex items-center gap-1.5 text-sm">
                        <span className="text-xs text-slate-400">已落定</span>
                        <span className={`font-bold ${completedCount === totalPlacedCount ? 'text-cosmic-cyan' : 'text-white'}`}>
                          {completedCount}/{totalPlacedCount}
                        </span>
                        {completedCount === totalPlacedCount && (
                          <span className="text-cosmic-cyan">✨</span>
                        )}
                      </div>
                    )}
                  </div>

                  {/* Dynamic Time Gaps Grid */}
                  <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 lg:grid-cols-4 xl:grid-cols-5 gap-3">
                    {gaps.map((gap) => {
                      const slotThoughts = dayThoughts.filter(t => t.placedSlotId === gap.id);
                      const semantic = GAP_SEMANTICS[gap.semantic];
                      const isLocked = gap.semantic === "GREY_LOCKED";

                      return (
                        <div 
                          key={gap.id} 
                          className={`border rounded-xl p-3 flex flex-col gap-2 min-h-[105px] transition-all ${isLocked ? 'bg-slate-900/35 border-slate-800 text-slate-500' : 'bg-cosmic-800/40 border-cosmic-700/40'}`}
                        >
                          <div className="border-b border-cosmic-700/40 pb-1.5 mb-1 flex items-center justify-between flex-wrap gap-1">
                            <span className="text-sm font-bold text-slate-300 truncate">
                              {gap.name}
                            </span>
                            <span className="text-xs text-slate-400 font-mono">
                              {gap.timeRange}
                            </span>
                          </div>

                          {/* Semantic tag */}
                          <div className="flex justify-start">
                            <span className={`text-xs px-1.5 py-0.5 rounded font-extrabold border ${semantic.color}`}>
                              {semantic.label}
                            </span>
                          </div>

                          <div className="flex-1 space-y-2 mt-1">
                            {isLocked ? (
                              <div className="h-full flex items-center justify-center py-2 text-xs text-slate-500 italic">
                                🔒 鎖定
                              </div>
                            ) : slotThoughts.length === 0 ? (
                              <span className="text-xs text-slate-600 font-medium italic block py-2 text-center">
                                空白時間
                              </span>
                            ) : (
                              slotThoughts.map(t => (
                                <div 
                                  key={t.id} 
                                  className={`p-2 rounded-lg border text-sm flex items-start gap-1.5 transition-all group ${t.status === 'COMPLETED' ? 'bg-cosmic-cyan/5 border-cosmic-cyan/20 opacity-60' : 'bg-cosmic-700/40 border-cosmic-600/40'}`}
                                >
                                  {/* Completion checkbox */}
                                  <button 
                                    onClick={() => toggleComplete(t)}
                                    className="mt-0.5 text-slate-400 hover:text-cosmic-cyan transition-colors flex-shrink-0"
                                  >
                                    {t.status === 'COMPLETED' ? (
                                      <CheckCircle2 className="w-3.5 h-3.5 text-cosmic-cyan" />
                                    ) : (
                                      <Circle className="w-3.5 h-3.5" />
                                    )}
                                  </button>

                                  <div className="flex-1 min-w-0">
                                    <p className={`font-medium break-words leading-snug ${t.status === 'COMPLETED' ? 'line-through text-slate-400' : 'text-slate-200'}`}>
                                      {t.title}
                                    </p>
                                    <div className="flex items-center gap-1.5 mt-1 flex-wrap">
                                      <span className={`text-xs px-1.5 py-0.5 rounded font-bold ${t.type === 'FOCUS' ? 'bg-cosmic-rose/10 text-cosmic-rose' : 'bg-cosmic-cyan/10 text-cosmic-cyan'}`}>
                                        {t.type === 'FOCUS' ? '專注' : '深思'}
                                      </span>
                                      {t.isDeadline && t.dueDate && (
                                        <span className="text-xs text-cosmic-gold bg-cosmic-gold/5 px-1.5 py-0.5 rounded border border-cosmic-gold/10">
                                          {t.dueDate}
                                        </span>
                                      )}
                                    </div>
                                  </div>

                                  {/* Put back to pending (Simulate Drag back) */}
                                  <button 
                                    onClick={() => returnToPending(t.id)}
                                    className="opacity-0 group-hover:opacity-100 p-0.5 text-slate-500 hover:text-cosmic-cyan rounded transition-all flex-shrink-0 self-start"
                                    title="退回待排區"
                                  >
                                    <ArrowLeftRight className="w-3 h-3" />
                                  </button>
                                </div>
                              ))
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      </main>

      {/* ==========================================
          OAUTH SETTINGS DIALOG
          ========================================== */}
      {showSettings && (
        <div className="fixed inset-0 bg-cosmic-900/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-cosmic-800 border border-cosmic-700 rounded-2xl w-full max-w-md p-6 shadow-2xl relative">
            <h3 className="text-lg font-bold text-white mb-3 flex items-center gap-2">
              <Settings className="w-5 h-5 text-cosmic-cyan" />
              設定 Google Client ID
            </h3>
            
            <p className="text-sm text-slate-300 leading-relaxed mb-4">
              為了能在您個人專屬的安全 Google Drive 中存取同步檔案，本網頁應用程式需要使用您在 Google Cloud Console 註冊的用戶端識別碼（Client ID）。
            </p>

            <div className="space-y-4 mb-5">
              <div>
                <label className="block text-sm font-bold text-slate-300 mb-2">OAuth 2.0 Web Client ID 🔑</label>
                <input 
                  type="text" 
                  value={clientId}
                  onChange={(e) => setClientId(e.target.value.trim())}
                  placeholder="輸入 718xxx-xxx.apps.googleusercontent.com"
                  className="w-full bg-cosmic-700/60 border border-cosmic-600/50 rounded-xl px-4 py-2.5 text-sm text-white placeholder-slate-500 focus:outline-none focus:border-cosmic-cyan"
                />
              </div>

              <div className="bg-cosmic-900/60 p-3.5 rounded-xl text-xs text-slate-400 space-y-2">
                <p className="font-bold text-slate-300">如何取得 Client ID？</p>
                <ol className="list-decimal list-inside space-y-1">
                  <li>進入 <a href="https://console.cloud.google.com/" target="_blank" className="text-cosmic-cyan underline">Google Cloud Console</a> 並建立/選擇專案。</li>
                  <li>啟用 <span className="text-slate-200">Google Drive API</span>。</li>
                  <li>至「憑證」建立 <span className="text-slate-200">OAuth 用戶端 ID</span> (應用程式類型選「網頁應用程式」)。</li>
                  <li>將您的託管網址 (或 <span className="text-slate-200">http://localhost:3000</span> 用於本機測試) 加入「已授權的 JavaScript 來源」。</li>
                  <li>複製產生的 Client ID 貼到上方。</li>
                </ol>
              </div>
            </div>

            <div className="flex justify-end gap-3 border-t border-cosmic-700/60 pt-4">
              <button 
                onClick={() => setShowSettings(false)}
                className="px-4 py-2 rounded-xl text-sm font-bold bg-cosmic-cyan text-cosmic-900 hover:opacity-95"
              >
                儲存並關閉
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ==========================================
          DAY TYPE ADJUSTMENT DIALOG
          ========================================== */}
      {editingDayIdx !== null && (
        <div className="fixed inset-0 bg-cosmic-900/80 backdrop-blur-sm z-50 flex items-center justify-center p-4">
          <div className="bg-cosmic-800 border border-cosmic-700 rounded-2xl w-full max-w-sm p-6 shadow-2xl">
            <h3 className="text-base font-bold text-white mb-2">
              調整 {DAY_NAMES[editingDayIdx]} ({getShortDateStringForIndex(editingDayIdx, weekOffset)}) 日型配置
            </h3>
            <p className="text-sm text-slate-400 mb-4">這會變更您在該日大腦可排入的時間縫隙配置：</p>

            <div className="space-y-2.5 mb-5">
              {Object.entries(DAY_TYPES).map(([typeKey, info]) => {
                const gaps = getGapsForDayType(typeKey, editingDayIdx);
                const activeGapsCount = gaps.filter(g => g.semantic !== "GREY_LOCKED").length;
                return (
                  <button
                    key={typeKey}
                    onClick={() => changeDayType(editingDayIdx, typeKey)}
                    className="w-full p-3.5 text-left border border-cosmic-700 rounded-xl hover:border-cosmic-cyan/40 hover:bg-white/5 transition-all flex items-center justify-between"
                  >
                    <span className="text-sm font-bold text-slate-100">{info.name}</span>
                    <span className="text-xs font-semibold text-slate-400">{activeGapsCount} 個可用縫隙</span>
                  </button>
                );
              })}
            </div>

            <div className="flex justify-end pt-2">
              <button 
                onClick={() => setEditingDayIdx(null)}
                className="text-sm text-slate-400 hover:text-slate-200 font-bold px-3 py-1.5"
              >
                取消
              </button>
            </div>
          </div>
        </div>
      )}

      {/* ==========================================
          LIMIT WARNING NOTIFICATION MODAL
          ========================================== */}
      {warningMessage && (
        <div className="fixed bottom-6 right-6 z-50 max-w-sm bg-cosmic-800 border border-cosmic-rose/40 rounded-2xl p-5 shadow-2xl flex items-start gap-3.5 animate-slide-in">
          <AlertTriangle className="w-5 h-5 text-cosmic-rose flex-shrink-0 mt-0.5" />
          <div className="flex-1">
            <h4 className="text-sm font-bold text-cosmic-rose">落念警示！🧠</h4>
            <p className="text-xs text-slate-200 mt-1 leading-relaxed">{warningMessage}</p>
            <button 
              onClick={() => {
                setWarningMessage('');
              }}
              className="mt-3 bg-cosmic-rose/10 hover:bg-cosmic-rose/20 text-cosmic-rose font-bold text-xs px-3 py-1.5 rounded-lg transition-colors"
            >
              我知道了
            </button>
          </div>
        </div>
      )}

      {/* ==========================================
          PENDING PLACEMENT CONFIRMATION DIALOG
          ========================================== */}
      {pendingPlacement && (
        <div className="fixed inset-0 bg-cosmic-900/80 backdrop-blur-sm z-50 flex items-center justify-center p-4 animate-fade-in">
          <div className="bg-cosmic-800 border border-cosmic-rose/40 rounded-2xl w-full max-w-sm p-6 shadow-2xl relative">
            <h3 className="text-base font-bold text-cosmic-rose flex items-center gap-2 mb-2">
              <AlertTriangle className="w-5 h-5" />
              排程警示
            </h3>
            <p className="text-sm text-slate-200 leading-relaxed mb-4">
              {pendingPlacement.message}
            </p>
            <div className="flex justify-end gap-3 border-t border-cosmic-700/60 pt-4">
              <button
                onClick={() => setPendingPlacement(null)}
                className="px-3.5 py-2 rounded-xl text-sm font-bold bg-cosmic-700 hover:bg-cosmic-600 text-slate-300"
              >
                換一格
              </button>
              <button
                onClick={() => {
                  placeThoughtDirect(pendingPlacement.thoughtId, pendingPlacement.dayIndex, pendingPlacement.slotId);
                  setPendingPlacement(null);
                }}
                className="px-4 py-2 rounded-xl text-sm font-bold bg-cosmic-rose text-white hover:opacity-95"
              >
                強行安放
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Footer Branding */}
      <footer className="mt-12 border-t border-cosmic-800/80 py-6 text-center text-slate-500 text-xs font-medium">
        <p>© {new Date().getFullYear()} 落念 Luodi. 釋放思緒，減輕負荷，安然落定。</p>
      </footer>
    </div>
  );
}
