# 落念 Luodi - Web Companion App 💻☁️

這是一套與「落念 Luodi」手機 App 風格高度一致、極簡而優雅的**瀏覽器網頁 Companion 版**。

透過一秒雙向同步技術，您在電腦瀏覽器上輸入的念頭，將能透過您的 Google 帳號安全、私密地同步到手機 App 的「腦海中的念頭」待排區中，實現零延遲、高隱私的跨平台思緒管理。

---

## ✨ 核心特色

1. **零成本且超安全：**
   - 採用 **Google Drive AppData** 儲存方案。所有同步資料直接儲存在您*個人*的 Google 雲端硬碟專屬隱私 App 資料夾（Hidden App Data Space）中。
   - 外部任何人、任何第三方伺服器均無權限讀取，100% 安全無毒。
   - 不會佔用您一般的 Google 雲端硬碟容量，亦不需要負擔任何伺服器/資料庫維護費。
2. **與手機 App 完美互通：**
   - 採用一致的大腦超載門檻提示（3-5個微載、6-10個中載、>10個超載）與日型配置系統。
   - 整合手動立即同步與自動背景上傳。
   - 智慧雙向合併機制（基於每個念頭/配置的最後更新時間戳 `updatedAt`），免除覆蓋與遺失疑慮。

---

## 🚀 快速開始 (本機運行)

確保您的電腦已安裝 [Node.js](https://nodejs.org/)。

1. **進入專案資料夾：**
   ```bash
   cd web-companion
   ```

2. **安裝依賴套件：**
   ```bash
   npm install
   ```

3. **啟動本機開發伺服器：**
   ```bash
   npm run dev
   ```
   *終端機將會顯示網址（通常為 `http://localhost:3000`）。*

---

## 🔑 設定 Google OAuth 用戶端識別碼 (Client ID)

要使網頁版可以順利連接 Google 登入並與雲端硬碟同步，您需要註冊一組 Google OAuth Client ID：

1. 開啟 [Google Cloud Console](https://console.cloud.google.com/)。
2. **建立或選擇一個專案**。
3. 在左側選單搜尋或選擇「API 和服務」>「庫（Library）」，搜尋 **Google Drive API** 並點擊**啟用（Enable）**。
4. 前往「API 和服務」>「OAuth 同意畫面」，選擇 `External`，填寫基本的應用程式名稱和您的電子郵件（此處為個人使用，其餘欄位可留空），並儲存。
5. 前往「憑證（Credentials）」頁面：
   - 點擊「建立憑證」> **OAuth 用戶端 ID**。
   - 應用程式類型選擇：**網頁應用程式 (Web Application)**。
   - **已授權的 JavaScript 來源**：加入您的託管網址。
     - 本機測試請加入：`http://localhost:3000`
     - 若未來部署到 GitHub Pages、Vercel 或 Netlify，請加入該部署後的網址（例如 `https://your-username.github.io`）。
6. 點擊「建立」，複製產生的 **用戶端識別碼 (Client ID)**。
7. 開啟落念網頁版，點擊右上角的 **齒輪 ⚙️ 按鈕**，貼上 Client ID 並儲存。即可暢行無阻地開始同步！

---

## 🌐 部署發佈

由於這是一個**純靜態 SPA (Single Page Application)** 網頁，不需要任何後端與伺服器，您可以免費部署到任何静态代管服務（例如：GitHub Pages、Vercel、Netlify 等）：

### 方式一：編譯為靜態檔案手動部署
```bash
npm run build
```
*這會產生一個 `dist` 資料夾，內含完整的 HTML, CSS, JS。直接將 `dist` 的內容上傳至任何靜態託管即可！*

### 方式二：使用 GitHub Pages
若您將本資料夾推送到 GitHub 倉庫，您可以使用 [GitHub Pages](https://pages.github.io/) 進行免費託管。

---

## 📜 授權條款

本 Web Companion App 同步軟體為「落念 Luodi」之周邊附屬工具，保障 100% 隱私與用戶控制權。
祝您釋放腦海壓力，安然落定思緒！✨
