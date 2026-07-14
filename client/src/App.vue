<template>
  <div class="app-stage">
    <div class="ambient-noise"></div>
    <div class="ambient-glow"></div>

    <header class="navbar">
      <div class="nav-content">
        <div class="brand">
          <div class="brand-mark">AI</div>
          <div class="brand-copy">
            <span class="brand-title">AI 视频解析问答平台</span>
            <span class="brand-subtitle">视频解析 · 文档知识库 · RAG 问答</span>
          </div>
        </div>

        <div class="nav-controls">
          <button v-if="!currentUser" class="auth-btn" @click="openAuthModal">
            <span class="btn-icon">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path><circle cx="12" cy="7" r="4"></circle></svg>
            </span>
            登录 / 注册
          </button>

          <div v-else class="user-profile">
            <span class="user-name">:: {{ currentUser.nickname }} ::</span>
            <button class="logout-btn" @click="logout" title="退出登录">
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path><polyline points="16 17 21 12 16 7"></polyline><line x1="21" y1="12" x2="9" y2="12"></line></svg>
            </button>
          </div>

          <div class="status-pill" :class="{ 'is-active': uploading }">
            <div class="status-dot"></div>
            <span class="status-text">{{ uploading ? '数据传输中...' : '系统就绪' }}</span>
          </div>
        </div>
      </div>
    </header>

    <main class="main-container">
      <section class="console-intro">
        <div>
          <p class="eyebrow">CONTENT INTELLIGENCE CONSOLE</p>
          <h1>AI 视频解析与知识库问答平台</h1>
          <p class="intro-subtitle">上传视频或文档，完成转写、总结、检索与问答</p>
        </div>
        <div class="intro-metrics">
          <span>视频任务：{{ list.length }}</span>
          <span>知识库文档：{{ ragDocuments.length }}</span>
        </div>
      </section>

      <div class="workspace-tabs">
        <button
            class="workspace-tab"
            :class="{ active: activeWorkspace === 'video' }"
            @click="activeWorkspace = 'video'"
        >
          视频内容解析
        </button>
        <button
            class="workspace-tab"
            :class="{ active: activeWorkspace === 'rag' }"
            @click="activeWorkspace = 'rag'"
        >
          知识库问答
        </button>
      </div>

      <transition name="toast-pop">
        <div v-if="message" class="notification-bar" :class="{ 'error': message.startsWith('❌') || message.startsWith('⚠️') }">
          {{ message }}
        </div>
      </transition>

      <div class="workbench-shell">
        <div class="workbench-main">
          <section v-show="activeWorkspace === 'video'" class="video-workspace">
            <section class="hero-section">
              <div class="section-header">
                <div>
                  <p class="panel-kicker">VIDEO UNDERSTANDING</p>
                  <h3>视频内容解析</h3>
                </div>
                <div class="count-chip">{{ list.length }} 个任务</div>
              </div>

              <div class="upload-wrapper">
                <input
                    type="file"
                    id="file-input"
                    @change="handleFileChange"
                    accept="video/*"
                    hidden
                />

                <div
                    class="upload-magnet"
                    :class="{ 'processing': uploading, 'is-dragover': isDragOver }"
                    @dragover.prevent="isDragOver = true"
                    @dragleave.prevent="isDragOver = false"
                    @drop.prevent="handleDrop"
                >
                  <div class="split-container" v-if="!uploading">

                    <label for="file-input" class="skew-pane pane-local">
                      <div class="pane-content unskew">
                        <div class="magnet-icon">
                          <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path><polyline points="17 8 12 3 7 8"></polyline><line x1="12" y1="3" x2="12" y2="15"></line></svg>
                        </div>
                        <span class="magnet-title">LOCAL FILE</span>
                        <span class="magnet-desc">{{ isDragOver ? '松手上传' : '点击 / 拖拽本地视频' }}</span>
                      </div>
                    </label>

                    <div class="split-gap"></div>

                    <div class="skew-pane pane-url">
                      <div class="pane-content unskew">
                        <div class="magnet-icon">
                          <svg width="42" height="42" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><circle cx="12" cy="12" r="10"></circle><line x1="2" y1="12" x2="22" y2="12"></line><path d="M12 2a15.3 15.3 0 0 1 4 10 15.3 15.3 0 0 1-4 10 15.3 15.3 0 0 1 4-10z"></path></svg>
                        </div>
                        <span class="magnet-title">WEB LINK</span>
                        <span class="magnet-desc">B站 / YouTube / 抖音</span>

                        <div class="url-input-box" @click.stop>
                          <input
                              v-model="videoUrl"
                              type="text"
                              placeholder="粘贴视频链接..."
                              @keyup.enter="handleUrlUpload"
                          />
                          <button class="url-go-btn" @click="handleUrlUpload">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round" stroke-linejoin="round"><polyline points="9 18 15 12 9 6"></polyline></svg>
                          </button>
                        </div>
                      </div>
                    </div>

                  </div>

                  <div class="magnet-content busy" v-else>
                    <div class="quantum-loader"></div>
                    <span class="busy-text">正在建立通道并解析资源...</span>
                  </div>

                  <div class="border-glow"></div>
                </div>
              </div>
            </section>

            <section class="workspace-section">
              <div class="section-header">
                <div>
                  <p class="panel-kicker">TASK QUEUE</p>
                  <h3>视频任务列表</h3>
                </div>
                <div class="count-chip">{{ list.length }} 个任务</div>
              </div>
              <div v-if="list.length === 0" class="empty-state">暂无视频任务，上传视频后会在这里显示解析队列。</div>
              <div v-else class="card-grid">
                <div v-for="item in list" :key="item.id" class="project-card">

                  <button class="delete-btn" @click.stop="deleteItem(item)" title="删除此项">
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                      <line x1="18" y1="6" x2="6" y2="18"></line>
                      <line x1="6" y1="6" x2="18" y2="18"></line>
                    </svg>
                  </button>
                  <div class="card-meta">
                    <div class="meta-icon">
                      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polygon points="23 7 16 12 23 17 23 7"></polygon><rect x="1" y="5" width="15" height="14" rx="2" ry="2"></rect></svg>
                    </div>
                    <div class="meta-info">
                      <div class="filename-mask" :title="item.filename">{{ item.filename }}</div>
                      <div class="meta-tags">
                        <span class="time-tag">{{ formatTime(item.uploadTime) }}</span>
                        <span class="status-indicator" :class="item.status.toLowerCase()">
                          {{ item.status === 'COMPLETED' ? 'READY' : 'PROCESSING' }}
                        </span>
                      </div>
                    </div>
                  </div>

                  <div class="action-dock">
                    <button class="dock-item" @click="downloadAudio(item)">
                      <span class="item-icon">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 18V5l12-2v13"></path><circle cx="6" cy="18" r="3"></circle><circle cx="18" cy="16" r="3"></circle></svg>
                      </span>
                      <span class="item-label">下载音频</span>
                    </button>

                    <button
                        class="dock-item"
                        :disabled="item.status !== 'COMPLETED'"
                        @click="transcribe(item.id)"
                    >
                      <span class="item-icon">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
                      </span>
                      <span class="item-label">提取文字</span>
                    </button>

                    <button
                        class="dock-item ai-core"
                        :disabled="item.status !== 'COMPLETED' || isAnalysisSubmitting(item.id)"
                        @click="aiAnalyze(item.id)"
                    >
                      <span class="item-icon">
                        <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="4" y="4" width="16" height="16" rx="2" ry="2"></rect><rect x="9" y="9" width="6" height="6"></rect><line x1="9" y1="1" x2="9" y2="4"></line><line x1="15" y1="1" x2="15" y2="4"></line><line x1="9" y1="20" x2="9" y2="23"></line><line x1="15" y1="20" x2="15" y2="23"></line><line x1="20" y1="9" x2="23" y2="9"></line><line x1="20" y1="14" x2="23" y2="14"></line><line x1="1" y1="9" x2="4" y2="9"></line><line x1="1" y1="14" x2="4" y2="14"></line></svg>
                      </span>
                      <div class="label-group">
                        <span class="item-label">{{ isAnalysisSubmitting(item.id) ? '提交中...' : 'AI 总结' }}</span>
                      </div>
                      <div class="shimmer"></div>
                    </button>
                  </div>
                </div>
              </div>
            </section>
          </section>

          <section v-show="activeWorkspace === 'rag'" class="rag-section">
            <div class="section-header">
              <div>
                <p class="panel-kicker">RETRIEVAL AUGMENTED QA</p>
                <h3>知识库 / RAG 问答</h3>
              </div>
              <div class="count-chip">用户 1</div>
            </div>

            <div class="rag-layout">
              <div class="rag-panel">
                <div class="rag-panel-title">文档上传</div>
                <div class="rag-upload-row">
                  <input
                      type="file"
                      accept=".txt,.md"
                      class="rag-file-input"
                      @change="handleRagFileChange"
                  />
                  <button class="rag-action-btn" :disabled="ragUploading || !ragFile" @click="uploadRagDocument">
                    {{ ragUploading ? '上传中...' : '上传文档' }}
                  </button>
                  <button class="rag-action-btn ghost" :disabled="ragListLoading" @click="fetchRagDocuments">
                    {{ ragListLoading ? '加载中...' : '刷新列表' }}
                  </button>
                </div>
                <p v-if="ragMessage" class="rag-message" :class="{ error: ragError }">{{ ragMessage }}</p>

                <div class="rag-doc-list">
                  <div v-if="ragDocuments.length === 0" class="rag-empty">暂无知识库文档</div>
                  <div v-for="doc in ragDocuments" :key="doc.id" class="rag-doc-item">
                    <div class="rag-doc-main">
                      <span class="rag-doc-name" :title="doc.originalFilename">{{ doc.originalFilename }}</span>
                      <div class="rag-doc-actions">
                        <span class="rag-doc-status" :class="(doc.status || '').toLowerCase()">{{ doc.status }}</span>
                        <button class="rag-delete-btn" @click="deleteRagDocument(doc)">删除</button>
                      </div>
                    </div>
                    <div class="rag-doc-meta">
                      <span>{{ doc.chunkCount || 0 }} chunks</span>
                      <span>{{ formatTime(doc.createTime) }}</span>
                    </div>
                  </div>
                </div>
              </div>

              <div class="rag-panel">
                <div class="rag-panel-title">知识库提问</div>
                <textarea
                    v-model="ragQuestion"
                    class="rag-question"
                    placeholder="输入你的问题，例如：Spring Boot 是什么？"
                ></textarea>
                <button class="rag-action-btn ask" :disabled="ragAsking || !ragQuestion.trim()" @click="askRag">
                  {{ ragAsking ? '检索并生成中...' : '提问' }}
                </button>
              </div>
            </div>
          </section>
        </div>

        <aside class="result-panel" :class="{ 'has-result': sidebar.visible }">
          <div class="sidebar-header">
            <div class="sidebar-title">
              <span class="icon" v-if="sidebar.type === 'ai' || sidebar.type === 'rag'">
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M2 12h2"></path><path d="M20 12h2"></path><path d="M12 2v2"></path><path d="M12 20v2"></path><path d="M20.2 6.47l-1.4 1.4"></path><path d="M15.9 5.35l-1.4-1.4"></path><path d="M9 11a3 3 0 1 0 6 0a3 3 0 0 0-6 0"></path></svg>
              </span>
              <span class="icon" v-else>
                <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"></path><polyline points="14 2 14 8 20 8"></polyline><line x1="16" y1="13" x2="8" y2="13"></line><line x1="16" y1="17" x2="8" y2="17"></line><polyline points="10 9 9 9 8 9"></polyline></svg>
              </span>
              {{ sidebar.visible ? sidebar.title : 'AI 结果面板' }}
            </div>
            <button v-if="sidebar.visible" class="close-btn" @click="closeSidebar">×</button>
          </div>
          <div class="sidebar-body">
            <div v-if="!sidebar.visible" class="result-empty">
              <div class="result-empty-icon">AI</div>
              <p>选择视频任务生成总结，或在知识库中提问，AI 结果会显示在这里。</p>
            </div>
            <div v-else-if="sidebar.loading" class="loading-state"><div class="quantum-loader small"></div><p>数据流处理中...</p></div>
            <div v-else>
              <div v-if="sidebar.type === 'ai' || sidebar.type === 'rag'" class="markdown-content" v-html="renderedMarkdown"></div>
              <div v-else class="text-content"><pre>{{ sidebar.content }}</pre></div>

              <div v-if="sidebar.type === 'rag' && ragSources.length > 0" class="result-sources">
                <div class="rag-result-title">SOURCES</div>
                <details v-for="source in ragSources" :key="source.chunkId" class="rag-source-item" open>
                  <summary>
                    chunk #{{ source.chunkIndex }}
                    <span>score {{ formatScore(source.score) }}</span>
                  </summary>
                  <pre>{{ source.content }}</pre>
                </details>
              </div>
            </div>
          </div>
        </aside>
      </div>

      <div v-if="showAuthModal" class="auth-backdrop">
        <div class="auth-panel">
          <div class="auth-header">
            <h2 class="auth-title">{{ authMode === 'login' ? '用户登录' : '新用户注册' }}</h2>
            <button class="close-btn" @click="closeAuthModal">×</button>
          </div>
          <div class="auth-body">
            <div class="input-group">
              <label>USERNAME</label>
              <input v-model="authForm.username" type="text" placeholder="输入账号" />
            </div>
            <div class="input-group">
              <label>PASSWORD</label>
              <input v-model="authForm.password" type="password" placeholder="输入密码" />
            </div>
            <div class="input-group" v-if="authMode === 'register'">
              <label>NICKNAME (昵称)</label>
              <input v-model="authForm.nickname" type="text" placeholder="设置一个好听的名字" />
            </div>
            <div class="auth-action">
              <button class="cyber-btn" @click="handleAuth" :disabled="authLoading">
                <span v-if="!authLoading">{{ authMode === 'login' ? '立即登录' : '提交注册' }}</span>
                <span v-else>请求处理中...</span>
              </button>
            </div>
            <div class="auth-toggle">
              <span class="toggle-text">{{ authMode === 'login' ? '没有账号?' : '已有账号?' }}</span>
              <button class="toggle-link" @click="switchAuthMode">{{ authMode === 'login' ? '去注册' : '去登录' }}</button>
            </div>
            <p v-if="authMessage" class="auth-msg" :class="{'error': authError}">{{ authMessage }}</p>
          </div>
        </div>
      </div>
    </main>
  </div>
</template>

<script setup>
import { ref, onMounted, computed } from 'vue'
import { marked } from 'marked'

// --- 变量定义 ---
const file = ref(null)
const videoUrl = ref('')
const message = ref('')
const uploading = ref(false)
const list = ref([])
const isDragOver = ref(false)
const activeWorkspace = ref('video')
const sidebar = ref({ visible: false, type: 'ai', title: '', content: '', loading: false })
const currentUser = ref(null)
const showAuthModal = ref(false)
const authMode = ref('login')
const authLoading = ref(false)
const authMessage = ref('')
const authError = ref(false)
const authForm = ref({ username: '', password: '', nickname: '' })
const pollingTimers = ref({})
const AUTH_TOKEN_STORAGE_KEY = 'authToken'
const authToken = ref(localStorage.getItem(AUTH_TOKEN_STORAGE_KEY) || '')
const analysisSubmitting = ref({})
const ragFile = ref(null)
const ragDocuments = ref([])
const ragQuestion = ref('')
const ragAnswer = ref('')
const ragSources = ref([])
const ragUploading = ref(false)
const ragListLoading = ref(false)
const ragAsking = ref(false)
const ragMessage = ref('')
const ragError = ref(false)

const authenticatedFetch = (url, options = {}) => {
  const headers = new Headers(options.headers || {})
  if (authToken.value) headers.set('Authorization', `Bearer ${authToken.value}`)
  return fetch(url, { ...options, headers })
}

// Markdown 解析
const renderedMarkdown = computed(() => {
  if (!sidebar.value.content) return ''
  let cleanText = sidebar.value.content.replace(/<think>[\s\S]*?<\/think>/gi, "")
  if (cleanText.includes("</think>")) cleanText = cleanText.split("</think>").pop()
  if (!cleanText.trim()) cleanText = sidebar.value.content
  return marked.parse(cleanText)
})

// --- 核心业务逻辑 ---

const handleFileChange = async (e) => {
  if (!currentUser.value) {
    e.target.value = ''
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  const selectedFile = e.target.files[0]
  if (!selectedFile) return
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

const handleDrop = async (e) => {
  isDragOver.value = false
  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }
  const droppedFiles = e.dataTransfer.files
  if (!droppedFiles || droppedFiles.length === 0) return
  const selectedFile = droppedFiles[0]
  if (!selectedFile.type.startsWith('video/')) {
    showMsg('⚠️ 仅支持上传视频文件', true)
    return
  }
  file.value = selectedFile
  videoUrl.value = ''
  await uploadFile()
}

const CHUNK_SIZE = 5 * 1024 * 1024

const uploadFile = async () => {
  if (!file.value) return
  uploading.value = true
  const selectedFile = file.value
  const totalChunks = Math.ceil(selectedFile.size / CHUNK_SIZE)
  const storageKey = `upload:${selectedFile.name}:${selectedFile.size}:${selectedFile.lastModified}`

  try {
    let uploadId = localStorage.getItem(storageKey)
    let uploadedChunks = new Set()

    if (uploadId) {
      const statusRes = await authenticatedFetch(`http://localhost:9090/media/upload-status?uploadId=${encodeURIComponent(uploadId)}`)
      if (statusRes.ok) {
        uploadedChunks = new Set(await statusRes.json())
      } else {
        localStorage.removeItem(storageKey)
        uploadId = null
      }
    }

    if (!uploadId) {
      const params = new URLSearchParams({
        filename: selectedFile.name,
        totalChunks: String(totalChunks)
      })
      const initRes = await authenticatedFetch(`http://localhost:9090/media/init-upload?${params}`, { method: 'POST' })
      const initText = await initRes.text()
      if (!initRes.ok) throw new Error(initText || 'Failed to initialize upload')
      uploadId = initText
      localStorage.setItem(storageKey, uploadId)
    }

    for (let index = 0; index < totalChunks; index++) {
      if (uploadedChunks.has(index)) continue

      message.value = `正在上传分片 ${index + 1}/${totalChunks}...`
      const formData = new FormData()
      formData.append('uploadId', uploadId)
      formData.append('chunkIndex', String(index))
      formData.append('totalChunks', String(totalChunks))
      formData.append('file', selectedFile.slice(index * CHUNK_SIZE, Math.min(selectedFile.size, (index + 1) * CHUNK_SIZE)))

      const chunkRes = await authenticatedFetch('http://localhost:9090/media/upload-chunk', {
        method: 'POST',
        body: formData
      })
      if (!chunkRes.ok) throw new Error(await chunkRes.text() || `Chunk ${index} failed`)
    }

    message.value = '分片上传完成，正在合并文件...'
    const completeParams = new URLSearchParams({ uploadId })
    const completeRes = await authenticatedFetch(`http://localhost:9090/media/complete-upload?${completeParams}`, { method: 'POST' })
    if (!completeRes.ok) throw new Error(await completeRes.text() || 'Upload merge failed')

    localStorage.removeItem(storageKey)
    showMsg('✅ 本地上传完成')
    fetchList()
  } catch (error) {
    console.error(error)
    showMsg('❌ 上传失败: ' + error.message, true)
  } finally {
    uploading.value = false
  }
}

// 【链接上传 - 修复版】
const handleUrlUpload = async () => {
  if (!videoUrl.value) return

  if (!currentUser.value) {
    showMsg('⚠️ 权限受限：请先登录系统', true)
    openAuthModal()
    return
  }

  // 简单校验链接
  if (!videoUrl.value.startsWith('http')) {
    showMsg('⚠️ 请输入合法的 http/https 链接', true)
    return
  }

  uploading.value = true
  message.value = '正在解析链接并极速下载 (低码率模式)...'

  const formData = new FormData()
  formData.append('url', videoUrl.value)

  try {
    const res = await authenticatedFetch('http://localhost:9090/media/upload-url', {
      method: 'POST',
      body: formData
    })
    // 【关键修复】现在后端会返回 500 状态码，这里能正确捕获错误了
    const text = await res.text()
    if (!res.ok) throw new Error(text)

    showMsg('✅ 链接资源已入库')
    videoUrl.value = ''
    fetchList()
  } catch (error) {
    console.error(error)
    // 提取后端传来的具体错误信息
    let errMsg = error.message
    if (errMsg.includes("Unsupported URL")) errMsg = "不支持该平台链接"
    showMsg('❌ 解析失败: ' + errMsg, true)
  } finally {
    uploading.value = false
  }
}

const showMsg = (msg, isError = false) => {
  message.value = msg
  setTimeout(() => { if(message.value === msg) message.value = '' }, 4000)
}

const showRagMsg = (msg, isError = false) => {
  ragMessage.value = msg
  ragError.value = isError
}

const handleRagFileChange = (e) => {
  const selectedFile = e.target.files[0]
  if (!selectedFile) {
    ragFile.value = null
    return
  }
  const lowerName = selectedFile.name.toLowerCase()
  if (!lowerName.endsWith('.txt') && !lowerName.endsWith('.md')) {
    ragFile.value = null
    e.target.value = ''
    showRagMsg('仅支持 txt / md 文档', true)
    return
  }
  ragFile.value = selectedFile
  showRagMsg(`已选择：${selectedFile.name}`)
}

const uploadRagDocument = async () => {
  if (!ragFile.value) return
  ragUploading.value = true
  showRagMsg('正在上传并切块入库...')

  const formData = new FormData()
  formData.append('file', ragFile.value)

  try {
    const res = await authenticatedFetch('http://localhost:9090/knowledge/upload', {
      method: 'POST',
      body: formData
    })
    const text = await res.text()
    if (!res.ok) throw new Error(text || '知识库文档上传失败')

    const data = JSON.parse(text)
    showRagMsg(`上传成功：documentId=${data.documentId}, chunks=${data.chunkCount}`)
    ragFile.value = null
    await fetchRagDocuments()
  } catch (error) {
    console.error(error)
    showRagMsg('上传失败：' + error.message, true)
  } finally {
    ragUploading.value = false
  }
}

const fetchRagDocuments = async () => {
  ragListLoading.value = true
  try {
    const res = await authenticatedFetch('http://localhost:9090/knowledge/list')
    if (!res.ok) throw new Error(await res.text() || '文档列表加载失败')
    ragDocuments.value = await res.json()
  } catch (error) {
    console.error(error)
    showRagMsg('文档列表加载失败：' + error.message, true)
  } finally {
    ragListLoading.value = false
  }
}

const deleteRagDocument = async (doc) => {
  if (!doc || !doc.id) return
  if (!confirm(`确认删除知识库文档 "${doc.originalFilename}" 吗？`)) return

  try {
    const res = await authenticatedFetch(`http://localhost:9090/knowledge/document/${doc.id}`, {
      method: 'DELETE'
    })
    const text = await res.text()
    if (!res.ok) throw new Error(text || '删除失败')

    showRagMsg('删除成功')
    const shouldClearResult = ragSources.value.some(source => Number(source.documentId) === Number(doc.id))
    if (shouldClearResult) {
      ragAnswer.value = ''
      ragSources.value = []
      sidebar.value.visible = false
      sidebar.value.loading = false
      sidebar.value.content = ''
    }
    await fetchRagDocuments()
  } catch (error) {
    console.error(error)
    showRagMsg('删除失败：' + error.message, true)
  }
}

const askRag = async () => {
  if (!ragQuestion.value.trim()) return
  ragAsking.value = true
  ragAnswer.value = ''
  ragSources.value = []
  showRagMsg('')
  openSidebar('rag', 'RAG 问答结果')
  sidebar.value.content = '正在检索知识库并生成回答...'

  try {
    const res = await authenticatedFetch('http://localhost:9090/knowledge/ask', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        question: ragQuestion.value.trim(),
        topK: 5
      })
    })
    const text = await res.text()
    if (!res.ok) throw new Error(text || '问答失败')
    const data = JSON.parse(text)

    ragAnswer.value = data.answer || ''
    ragSources.value = Array.isArray(data.sources) ? data.sources : []
    sidebar.value.content = ragAnswer.value || '未生成有效回答'
    sidebar.value.loading = false
  } catch (error) {
    console.error(error)
    showRagMsg('提问失败：' + error.message, true)
    sidebar.value.content = '提问失败：' + error.message
    sidebar.value.loading = false
  } finally {
    ragAsking.value = false
  }
}

const formatScore = (score) => {
  const value = Number(score)
  return Number.isFinite(value) ? value.toFixed(4) : '0.0000'
}

const fetchList = async () => {
  try {
    let url = 'http://localhost:9090/media/list'
    if (currentUser.value) {
      // 【核心修改】加一个 _t 时间戳，强制浏览器每次都发新请求，不许读缓存！
      const timestamp = new Date().getTime()
      url += `?_t=${timestamp}`

      const res = await authenticatedFetch(url)
      const data = await res.json()
      // 倒序排列，新的在前面
      list.value = data.reverse()
    } else {
      list.value = []
    }
  } catch (error) {
    console.error(error)
  }
}

const deleteItem = async (item) => {
  if (!confirm(`确认要永久删除 "${item.filename}" 吗？`)) return
  try {
    const url = `http://localhost:9090/media/delete?id=${item.id}`
    const res = await authenticatedFetch(url, { method: 'DELETE' })
    const text = await res.text()
    if (text === '删除成功') {
      showMsg('文件已销毁')
      list.value = list.value.filter(i => i.id !== item.id)
    } else {
      showMsg('❌ ' + text, true)
    }
  } catch (e) {
    showMsg('❌ 删除请求失败', true)
  }
}

const formatTime = (timeStr) => {
  if (!timeStr) return '--'
  const date = new Date(timeStr)
  return `${date.getMonth() + 1}/${date.getDate()} ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

const downloadAudio = async (item) => {
  const url = `http://localhost:9090/debug/download?id=${item.id}`
  let fileName = item.filename || 'audio.mp3';
  fileName = fileName.replace(/\.[^/.]+$/, "") + ".mp3";
  try {
    showMsg('正在转码并下载...')
    const res = await fetch(url)
    if(!res.ok) throw new Error("Fail")
    const blob = await res.blob()
    const downloadUrl = window.URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = downloadUrl
    link.download = fileName
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    window.URL.revokeObjectURL(downloadUrl)
    showMsg('✅ 下载完成')
  } catch (e) {
    alert("下载失败")
  }
}

const transcribe = async (id) => {
  const item = list.value.find(i => i.id === id)
  if (item && item.transcriptText) {
    openSidebar('text', '全量文字提取')
    sidebar.value.content = item.transcriptText
    sidebar.value.loading = false
    return
  }
  if (pollingTimers.value[id] && pollingTimers.value[id].type === 'text') {
    openSidebar('text', '全量文字提取')
    sidebar.value.loading = true
    sidebar.value.content = "📝 文字提取正在后台进行中..."
    return
  }
  openSidebar('text', '全量文字提取')
  sidebar.value.loading = true
  sidebar.value.content = "📝 提取任务已提交，正在识别语音流..."
  try {
    await fetch(`http://localhost:9090/debug/transcribe?id=${id}`)
    startPolling(id, 'text')
  } catch (e) {
    sidebar.value.content = "Error: " + e
    sidebar.value.loading = false
  }
}

const isAnalysisSubmitting = (id) => Boolean(analysisSubmitting.value[id])

const setAnalysisSubmitting = (id, submitting) => {
  const next = { ...analysisSubmitting.value }
  if (submitting) next[id] = true
  else delete next[id]
  analysisSubmitting.value = next
}

const clearAuthenticatedSession = () => {
  currentUser.value = null
  authToken.value = ''
  localStorage.removeItem('user')
  localStorage.removeItem(AUTH_TOKEN_STORAGE_KEY)
  list.value = []
  ragDocuments.value = []
  ragAnswer.value = ''
  ragSources.value = []
}

const parseResponseBody = async (response) => {
  const text = await response.text()
  if (!text) return { data: null, message: '' }
  try {
    const data = JSON.parse(text)
    return { data, message: data.message || data.msg || text }
  } catch (_) {
    return { data: null, message: text }
  }
}

const isAnalysisPlaceholder = (summary) => {
  if (!summary) return false
  const normalized = summary.toLowerCase()
  return summary.includes('任务已')
      || summary.includes('正在')
      || normalized.includes('[mq]')
      || normalized.includes('queued')
}

// 正式 AI 分析入口：登录 Token -> 归属校验 -> 限流/防重 -> RocketMQ。
const aiAnalyze = async (id, force = false) => {
  const item = list.value.find(i => i.id === id)

  // 1. 如果已经有结果，直接显示
  if (!force && item && item.aiSummary && !isAnalysisPlaceholder(item.aiSummary)) {
    openSidebar('ai', 'AI 智能总结')
    sidebar.value.content = item.aiSummary
    sidebar.value.loading = false
    return
  }

  // 2. 如果正在轮询，直接打开侧边栏
  if (pollingTimers.value[id] && pollingTimers.value[id].type === 'ai') {
    openSidebar('ai', 'AI 智能总结')
    sidebar.value.loading = true
    sidebar.value.content = "🚀 系统正在后台拼命计算中...\n\n(任务正在进行，无需重复提交)"
    return
  }

  // Vue 的 disabled 更新前仍可能收到第二次点击，因此函数入口也做同步防并发。
  if (isAnalysisSubmitting(id)) return

  if (!currentUser.value || !authToken.value) {
    showMsg('⚠️ 请重新登录后再提交分析任务', true)
    openAuthModal()
    return
  }

  // 3. 准备提交请求，打开侧边栏loading
  setAnalysisSubmitting(id, true)
  openSidebar('ai', 'AI 智能总结')
  sidebar.value.loading = true
  sidebar.value.content = "🚀 正在提交正式分析任务..."

  try {
    const res = await authenticatedFetch(`http://localhost:9090/media/analyze/${id}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({})
    })
    const { data, message: responseMessage } = await parseResponseBody(res)

    if (res.status === 401) {
      clearAuthenticatedSession()
      sidebar.value.visible = false
      sidebar.value.loading = false
      showMsg('⚠️ 登录已失效，请重新登录', true)
      openAuthModal()
      return
    }

    if (res.status === 403) {
      sidebar.value.loading = false
      sidebar.value.content = responseMessage || '无权分析该视频'
      showMsg('⚠️ 无权分析该视频', true)
      return
    }

    if (!res.ok) {
      sidebar.value.loading = false
      sidebar.value.content = responseMessage || `分析任务提交失败（HTTP ${res.status}）`
      showMsg(`❌ ${sidebar.value.content}`, true)
      return
    }

    const status = data?.status
    if (status === 'RUNNING') {
      const duplicateMessage = responseMessage || 'Analysis task is already running'
      showMsg(`⚠️ ${duplicateMessage}`, true)
      sidebar.value.content = `${duplicateMessage}\n\n⏳ 已存在运行中的任务，继续等待处理结果...`
    } else {
      showMsg('✅ 任务已提交')
      sidebar.value.content = `${responseMessage || '任务已提交'}\n\n⏳ 等待消费者接单处理...`
    }
    startPolling(id, 'ai')

  } catch (e) {
    sidebar.value.content = "Error: " + (e?.message || e)
    sidebar.value.loading = false
    showMsg('❌ 分析请求失败，请检查网络或后端服务', true)
  } finally {
    setAnalysisSubmitting(id, false)
  }
}

const startPolling = (id, type) => {
  // 清理旧定时器
  if (pollingTimers.value[id]) clearInterval(pollingTimers.value[id].timer)
  console.log(`[轮询] 开始监听任务 ID: ${id}, 类型: ${type}`)

  const timer = setInterval(async () => {
    // 1. 强制刷新列表 (带时间戳防止缓存)
    await fetchList()
    const item = list.value.find(i => i.id === id)
    if (!item) return

    let isFinished = false
    let result = ''

    if (type === 'ai') {
      const text = item.aiSummary || ''

      // 【核心修改】纯文本判断逻辑，绝对不使用 Emoji
      // 条件1: 成功 (包含 Markdown 的标题特征 "##")
      const isSuccess = text.includes("##");
      // 条件2: 失败 (包含错误关键词)
      const isError = text.includes("失败") || text.includes("Error") || text.includes("超时") || text.includes("500");

      // 只要是成功或失败，都视为“结束”，停止轮询
      if (isSuccess || isError) {
        isFinished = true
        result = text
      }

    } else if (type === 'text') {
      const text = item.transcriptText || ''
      // 文字提取同理：如果有内容且长度足够，或者报错，就停止
      if (text && (text.length > 10 || text.includes("失败"))) {
        isFinished = true
        result = text
      }
    }

    // 2. 结算
    if (isFinished) {
      // 如果侧边栏正开着，更新内容
      if (sidebar.value.visible && sidebar.value.title.includes(type === 'ai' ? 'AI' : '文字')) {
        sidebar.value.content = result
        sidebar.value.loading = false
      }

      // 只有成功才提示完成，报错则提示警告
      if (result.includes("失败") || result.includes("Error")) {
        showMsg("⚠️ 任务结束，但存在错误", true)
      } else {
        showMsg("✅ 任务完成")
      }

      clearInterval(timer)
      delete pollingTimers.value[id]
    }
  }, 3000) // 3秒轮询一次

  pollingTimers.value[id] = { timer, type }

  // 5分钟强制兜底停止
  setTimeout(() => {
    if (pollingTimers.value[id]) {
      clearInterval(pollingTimers.value[id].timer)
      delete pollingTimers.value[id]
    }
  }, 300000)
}

const openSidebar = (type, title) => {
  sidebar.value.visible = true
  sidebar.value.type = type
  sidebar.value.title = title
  sidebar.value.loading = true
  sidebar.value.content = ''
}
const closeSidebar = () => { sidebar.value.visible = false }

const openAuthModal = () => {
  showAuthModal.value = true
  authMessage.value = ''
  authForm.value = { username: '', password: '', nickname: '' }
}
const closeAuthModal = () => { showAuthModal.value = false }
const switchAuthMode = () => {
  authMode.value = authMode.value === 'login' ? 'register' : 'login'
  authMessage.value = ''
}
const handleAuth = async () => {
  if (!authForm.value.username || !authForm.value.password) {
    authMessage.value = '请输入完整的账号和密码'
    authError.value = true
    return
  }
  authLoading.value = true
  authMessage.value = ''
  const endpoint = authMode.value === 'login' ? '/user/login' : '/user/register'
  try {
    const res = await fetch(`http://localhost:9090${endpoint}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(authForm.value)
    })
    const data = await res.json()
    if (data.code === 200) {
      if (authMode.value === 'login') {
        if (!data.token) throw new Error('登录响应缺少 token')
        currentUser.value = data.userInfo
        authToken.value = data.token
        localStorage.setItem('user', JSON.stringify(data.userInfo))
        localStorage.setItem(AUTH_TOKEN_STORAGE_KEY, data.token)
        closeAuthModal()
        showMsg(`欢迎回来，${data.userInfo.nickname}`)
        fetchList()
        fetchRagDocuments()
      } else {
        authMessage.value = '注册成功，请直接登录'
        authError.value = false
        setTimeout(() => switchAuthMode(), 1000)
      }
    } else {
      authMessage.value = data.msg || '操作失败'
      authError.value = true
    }
  } catch (e) {
    console.error(e)
    authMessage.value = '网络连接错误'
    authError.value = true
  } finally {
    authLoading.value = false
  }
}
const logout = () => {
  clearAuthenticatedSession()
  showMsg('已退出系统')
}
onMounted(() => {
  const savedUser = localStorage.getItem('user')
  const savedToken = localStorage.getItem(AUTH_TOKEN_STORAGE_KEY)
  if (savedUser && savedToken) {
    try {
      currentUser.value = JSON.parse(savedUser)
      authToken.value = savedToken
    } catch(e) {
      clearAuthenticatedSession()
    }
  } else if (savedUser || savedToken) {
    clearAuthenticatedSession()
  }
  if (currentUser.value && authToken.value) {
    fetchList()
    fetchRagDocuments()
  }
})
</script>

<style>
/* 确保字体引用在最上方 */
@import url('https://fonts.googleapis.com/css2?family=Dela+Gothic+One&family=Noto+Sans+SC:wght@400;500;700&family=Space+Grotesk:wght@300;500;700&family=Syncopate:wght@700&display=swap');

:root {
  --bg-deep: #070b14;
  --bg-card: #111827;
  --accent-lime: #22d3ee;
  --accent-purple: #7c3aed;
  --accent-success: #5af28d;
  --text-main: #e5eefb;
  --text-sub: #7f8ca3;
  --text-inverse: #0b0c10;
  --border-tech: #263246;
  --shadow-float: 0 10px 30px -10px rgba(0, 0, 0, 0.7);
  --shadow-glow-lime: 0 0 20px rgba(34, 211, 238, 0.22);
}

* { box-sizing: border-box; margin: 0; padding: 0; }

html, body, #app {
  margin: 0 !important; padding: 0 !important; width: 100vw !important;
  max-width: 100vw !important; min-height: 100vh !important;
  overflow-x: hidden; background-color: var(--bg-deep);
}

.app-stage { position: relative; z-index: 1; width: 100%; min-height: 100vh; color: var(--text-main); font-family: 'Space Grotesk', 'Noto Sans SC', monospace; }

.ambient-noise { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 200 200' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='noiseFilter'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.65' numOctaves='3' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23noiseFilter)' opacity='0.05'/%3E%3C/svg%3E"); pointer-events: none; z-index: -1; }
.ambient-glow { position: fixed; top: -20%; left: 20%; width: 60vw; height: 60vh; background: radial-gradient(circle, rgba(34, 211, 238, 0.1) 0%, rgba(7, 11, 20, 0) 70%); pointer-events: none; z-index: -2; }

/* 导航 */
.navbar { position: sticky; top: 0; z-index: 100; width: 100%; padding: 1.2rem 0; background: rgba(11, 12, 16, 0.85); backdrop-filter: blur(12px); border-bottom: 1px solid var(--border-tech); }
.nav-content { max-width: 1400px; margin: 0 auto; padding: 0 2rem; display: flex; justify-content: space-between; align-items: center; }
.brand { display: flex; align-items: baseline; gap: 2px; }
.brand-do { font-family: 'Dela Gothic One', sans-serif; font-size: 1.8rem; color: var(--text-main); letter-spacing: -1px; }
.brand-video { font-family: 'Space Grotesk', sans-serif; font-size: 1.8rem; font-weight: 300; }
.beta-badge { font-size: 0.7rem; font-weight: 700; background: var(--accent-lime); color: var(--text-inverse); padding: 2px 6px; border-radius: 2px; margin-left: 8px; transform: translateY(-4px); box-shadow: 0 0 5px var(--accent-lime); }

.nav-controls { display: flex; align-items: center; gap: 15px; }
.auth-btn { background: transparent; border: 1px solid var(--border-tech); color: var(--accent-lime); padding: 6px 16px; border-radius: 4px; font-family: 'Noto Sans SC', sans-serif; font-weight: 700; cursor: pointer; display: flex; align-items: center; gap: 8px; transition: all 0.3s; font-size: 0.85rem; }
.auth-btn:hover { background: rgba(34, 211, 238, 0.1); border-color: var(--accent-lime); box-shadow: 0 0 10px rgba(34, 211, 238, 0.22); }
.user-profile { display: flex; align-items: center; gap: 10px; font-family: monospace; font-size: 0.9rem; color: var(--text-main); }
.user-name { color: var(--accent-lime); }
.logout-btn { background: none; border: none; color: var(--text-sub); cursor: pointer; padding: 4px; display: flex; align-items: center; transition: color 0.3s; }
.logout-btn:hover { color: #ff4757; }

.status-pill { display: flex; align-items: center; gap: 8px; background: var(--bg-card); padding: 6px 12px; border-radius: 4px; border: 1px solid var(--border-tech); font-size: 0.8rem; color: var(--text-sub); }
.status-dot { width: 6px; height: 6px; background: var(--accent-lime); border-radius: 50%; }
.status-pill.is-active .status-dot { animation: pulse-lime 1.5s infinite alternate; }

/* Hero */
.main-container { max-width: 1200px; margin: 0 auto; padding: 4rem 2rem; }
.hero-section { text-align: center; margin-bottom: 6rem; animation: slideUpFade 0.8s forwards; }
.slogan-main { font-family: 'Syncopate', sans-serif; font-size: clamp(2.5rem, 6vw, 4.5rem); font-weight: 700; margin-bottom: 0.5rem; text-shadow: 0 0 20px rgba(34, 211, 238, 0.22); }
.slogan-sub { font-size: 1.1rem; color: var(--text-sub); letter-spacing: 2px; margin-bottom: 3rem; }

/* === [START] 核心重构：Upload Wrapper (Physical Skew) === */
.upload-wrapper { max-width: 800px; margin: 0 auto; perspective: 1000px; opacity: 0; animation: slideUpFade 0.8s 0.2s forwards; }

.upload-magnet {
  position: relative; height: 300px;
  background: var(--bg-card);
  border-radius: 16px;
  box-shadow: var(--shadow-float);
  border: 2px solid var(--border-tech);
  overflow: hidden; /* 必须隐藏溢出 */
  transition: all 0.3s;
}
.upload-magnet:hover { border-color: var(--accent-lime); box-shadow: var(--shadow-glow-lime); transform: translateY(-5px); }

/* 容器布局 */
.split-container {
  display: flex; height: 100%; width: 100%;
  position: relative; overflow: hidden;
}

/* 左右面板 (物理倾斜) */
.skew-pane {
  flex: 1; height: 100%; position: relative; cursor: pointer;
  background: rgba(11, 12, 16, 0.5); /* 默认深色底 */
  transition: all 0.4s ease;
  display: flex; align-items: center; justify-content: center;
  z-index: 1;
  /* 核心：直接对容器进行 skew，而不是 clip-path */
  transform: skewX(-10deg);
}

/* 增加左右面板的宽度，确保覆盖边缘 */
.pane-local { margin-left: -20px; padding-right: 20px; border-right: 2px solid var(--accent-lime); }
.pane-url { margin-right: -20px; padding-left: 20px; }

/* 鼠标悬停逻辑：只改变背景色，不加外发光，防止穿模 */
.skew-pane:hover {
  background: rgba(34, 211, 238, 0.07);
  z-index: 10;
}

/* 中间缝隙 */
.split-gap { width: 4px; background: transparent; transform: skewX(-10deg); }

/* 内容回正 */
.pane-content {
  /* 必须反向 skew 回来，否则文字是斜的 */
  transform: skewX(10deg);
  display: flex; flex-direction: column; align-items: center;
  z-index: 2; transition: transform 0.3s;
}
.skew-pane:hover .pane-content { transform: skewX(10deg) scale(1.05); }

/* 互斥变暗 */
.split-container:has(.skew-pane:hover) .skew-pane:not(:hover) { opacity: 0.3; filter: grayscale(1); }

.magnet-icon { color: var(--accent-lime); margin-bottom: 1rem; filter: drop-shadow(0 0 5px var(--accent-lime)); }
.magnet-title { font-size: 1.4rem; font-weight: 700; letter-spacing: 1px; margin-bottom: 5px; font-family: 'Dela Gothic One', sans-serif; }
.magnet-desc { font-size: 0.8rem; color: var(--text-sub); font-family: monospace; }

/* URL 输入框 (需回正) */
.url-input-box {
  display: flex; margin-top: 15px; border-bottom: 2px solid var(--border-tech);
  transition: all 0.3s; position: relative; z-index: 30;
}
.skew-pane:hover .url-input-box { border-color: var(--accent-lime); }
.url-input-box input {
  background: transparent; border: none; outline: none; color: var(--text-main);
  font-family: monospace; padding: 8px 5px; width: 180px; font-size: 0.9rem;
}
.url-go-btn {
  background: transparent; border: none; color: var(--accent-lime); cursor: pointer;
  padding: 0 8px; opacity: 0.7; transition: all 0.3s;
}
.url-go-btn:hover { opacity: 1; transform: translateX(3px); }

/* 处理中状态 */
.magnet-content.busy {
  height: 100%; width: 100%; display: flex; flex-direction: column; align-items: center; justify-content: center;
  background: var(--bg-card); position: relative; z-index: 50;
}
.busy-text { margin-top: 15px; color: var(--accent-lime); font-family: monospace; animation: pulse-lime 2s infinite; }
/* === [END] 重构结束 === */

.notification-bar { margin-top: 2rem; display: inline-block; background: var(--accent-lime); color: var(--text-inverse); padding: 10px 24px; font-weight: 700; border-radius: 4px; clip-path: polygon(5% 0%, 100% 0%, 95% 100%, 0% 100%); }
.notification-bar.error { background: #ff4757; color: #fff; }

.quantum-loader { width: 50px; height: 50px; border: 4px solid var(--border-tech); border-top-color: var(--accent-lime); border-radius: 50%; animation: spin 0.8s linear infinite; margin-bottom: 1rem; box-shadow: 0 0 10px var(--accent-lime); }
.quantum-loader.small { width: 30px; height: 30px; margin: 0 auto; }

/* Workspace */
.workspace-section { opacity: 0; animation: slideUpFade 0.8s 0.4s forwards; }
.section-header { display: flex; align-items: center; gap: 12px; margin-bottom: 2rem; border-bottom: 2px solid var(--border-tech); padding-bottom: 10px; }
.section-header h3 { font-size: 1.5rem; font-weight: 700; }
.count-chip { background: var(--border-tech); padding: 4px 10px; border-radius: 4px; font-size: 0.75rem; font-family: monospace; }
.card-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 20px; }
.project-card { background: var(--bg-card); border-radius: 12px; box-shadow: 0 2px 10px rgba(0,0,0,0.3); border: 1px solid var(--border-tech); overflow: hidden; transition: transform 0.2s; position: relative; }
.project-card:hover { transform: translateY(-2px); border-color: var(--accent-lime); }
.card-meta { display: flex; gap: 1.5rem; padding: 1.5rem; align-items: center; border-bottom: 1px solid var(--border-tech); background: rgba(10, 18, 34, 0.58); }
.meta-icon { width: 56px; height: 56px; background: rgba(34, 211, 238, 0.08); border: 1px solid var(--accent-lime); border-radius: 8px; display: flex; align-items: center; justify-content: center; color: var(--accent-lime); }
.filename-mask { font-size: 1.1rem; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 180px; }
.meta-tags { display: flex; gap: 12px; font-size: 0.85rem; font-family: monospace; margin-top: 5px; }
.time-tag { color: var(--text-sub); }
.status-indicator { font-weight: 600; padding: 2px 8px; border-radius: 4px; }
.status-indicator.completed { color: var(--accent-success); border: 1px solid var(--accent-success); background: rgba(90, 242, 141, 0.1); }
.status-indicator.processing { color: var(--accent-purple); border: 1px solid var(--accent-purple); animation: blink 1s infinite; }

.action-dock { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; padding: 12px; background: rgba(8, 15, 30, 0.64); }
.dock-item { position: relative; min-width: 0; border: 1px solid var(--border-tech); background: var(--bg-card); border-radius: 8px; padding: 12px 8px; display: flex; align-items: center; justify-content: center; gap: 8px; cursor: pointer; transition: all 0.3s; color: var(--text-sub); font-family: monospace; overflow: hidden; white-space: nowrap; }
.dock-item svg { flex: 0 0 auto; }
.item-label { white-space: nowrap; }
.dock-item:hover:not(:disabled) { color: var(--accent-lime); border-color: var(--accent-lime); background: rgba(34, 211, 238, 0.08); }
.dock-item:disabled { opacity: 0.3; cursor: not-allowed; }
.dock-item.ai-core { border-color: var(--accent-purple); color: var(--accent-purple); }
.dock-item.ai-core .label-group { display: flex; flex-direction: column; align-items: flex-start; z-index: 1; }
.dock-item.ai-core .item-sub { font-size: 0.75rem; color: var(--accent-purple); opacity: 0.8; }
.dock-item.ai-core:hover:not(:disabled) { border-color: var(--accent-lime); color: var(--text-inverse); background: var(--accent-lime); }
.dock-item.ai-core:hover:not(:disabled) .item-sub { color: var(--text-inverse); }

/* RAG */
.rag-section { margin-top: 4rem; opacity: 0; animation: slideUpFade 0.8s 0.5s forwards; }
.rag-layout { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.2fr); gap: 20px; }
.rag-panel { background: var(--bg-card); border: 1px solid var(--border-tech); border-radius: 12px; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.3); }
.rag-panel:hover { border-color: rgba(34, 211, 238, 0.58); }
.rag-panel-title { color: var(--accent-lime); font-weight: 700; letter-spacing: 1px; margin-bottom: 16px; font-family: 'Noto Sans SC', monospace; }
.rag-upload-row { display: grid; grid-template-columns: minmax(0, 1fr) auto auto; gap: 10px; align-items: center; }
.rag-file-input { width: 100%; background: #000; border: 1px solid var(--border-tech); color: var(--text-main); padding: 10px; font-family: monospace; }
.rag-action-btn { border: 1px solid var(--accent-lime); background: var(--accent-lime); color: var(--text-inverse); padding: 10px 14px; border-radius: 4px; cursor: pointer; font-weight: 700; font-family: 'Noto Sans SC', sans-serif; transition: all 0.3s; white-space: nowrap; }
.rag-action-btn:hover:not(:disabled) { box-shadow: 0 0 14px rgba(34, 211, 238, 0.28); transform: translateY(-1px); }
.rag-action-btn:disabled { opacity: 0.45; cursor: not-allowed; }
.rag-action-btn.ghost { background: transparent; color: var(--accent-lime); }
.rag-action-btn.ask { width: 100%; margin-top: 12px; }
.rag-message { margin-top: 12px; color: var(--accent-lime); font-size: 0.85rem; font-family: 'Noto Sans SC', monospace; }
.rag-message.error { color: #ff4757; }
.rag-doc-list { margin-top: 18px; display: flex; flex-direction: column; gap: 10px; max-height: 320px; overflow-y: auto; }
.rag-empty { border: 1px dashed var(--border-tech); color: var(--text-sub); padding: 16px; text-align: center; border-radius: 8px; font-size: 0.9rem; }
.rag-doc-item { border: 1px solid var(--border-tech); border-radius: 8px; padding: 12px; background: rgba(8, 15, 30, 0.56); }
.rag-doc-main { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 8px; }
.rag-doc-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 700; }
.rag-doc-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.rag-doc-status { border: 1px solid var(--border-tech); border-radius: 4px; padding: 2px 8px; font-size: 0.75rem; font-family: monospace; }
.rag-delete-btn { border: 1px solid #ff4757; background: transparent; color: #ff8b96; border-radius: 4px; padding: 2px 8px; cursor: pointer; font-size: 0.75rem; font-family: 'Noto Sans SC', sans-serif; }
.rag-delete-btn:hover { background: rgba(255, 71, 87, 0.12); color: #fff; }
.rag-doc-status.ready { color: var(--accent-success); border-color: var(--accent-success); }
.rag-doc-status.failed { color: #ff4757; border-color: #ff4757; }
.rag-doc-status.processing { color: var(--accent-purple); border-color: var(--accent-purple); }
.rag-doc-meta { display: flex; justify-content: space-between; gap: 12px; color: var(--text-sub); font-size: 0.78rem; font-family: monospace; }
.rag-question { width: 100%; min-height: 110px; resize: vertical; background: #000; border: 1px solid var(--border-tech); color: var(--text-main); padding: 12px; border-radius: 8px; outline: none; font-family: 'Noto Sans SC', monospace; line-height: 1.6; }
.rag-question:focus { border-color: var(--accent-lime); box-shadow: 0 0 10px rgba(34, 211, 238, 0.22); }
.rag-answer, .rag-sources { margin-top: 18px; border-top: 1px solid var(--border-tech); padding-top: 16px; }
.rag-result-title { color: var(--text-sub); font-family: monospace; font-size: 0.75rem; letter-spacing: 1px; margin-bottom: 10px; }
.rag-answer p { white-space: pre-wrap; line-height: 1.8; color: var(--text-main); }
.rag-source-item { border: 1px solid var(--border-tech); border-radius: 8px; margin-bottom: 10px; background: rgba(8, 15, 30, 0.56); overflow: hidden; }
.rag-source-item summary { cursor: pointer; padding: 10px 12px; color: var(--accent-lime); display: flex; justify-content: space-between; gap: 12px; font-family: monospace; }
.rag-source-item pre { white-space: pre-wrap; margin: 0; padding: 12px; color: #d4d4d8; background: #000; border-top: 1px solid var(--border-tech); line-height: 1.6; font-family: 'Noto Sans SC', monospace; max-height: 220px; overflow-y: auto; }

@media (max-width: 860px) {
  .rag-layout { grid-template-columns: 1fr; }
  .rag-upload-row { grid-template-columns: 1fr; }
}

/* Sidebar */
.sidebar-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.6); backdrop-filter: blur(4px); z-index: 998; }
.sidebar-panel { position: fixed; top: 0; right: -600px; width: 550px; max-width: 90vw; height: 100%; background: var(--bg-card); border-left: 2px solid var(--accent-lime); z-index: 999; transition: right 0.4s cubic-bezier(0.19, 1, 0.22, 1); display: flex; flex-direction: column; box-shadow: -10px 0 40px rgba(0,0,0,0.8); }
.sidebar-panel.is-open { right: 0; }
.sidebar-header { padding: 20px 30px; border-bottom: 1px solid var(--border-tech); display: flex; justify-content: space-between; align-items: center; background: rgba(11, 12, 16, 0.9); }
.sidebar-title { font-size: 1.4rem; font-weight: 700; color: var(--text-main); display: flex; align-items: center; gap: 10px; }
.icon { color: var(--accent-lime); display: flex; align-items: center; }
.close-btn { background: none; border: none; color: var(--text-sub); padding: 5px; cursor: pointer; transition: color 0.3s; }
.close-btn:hover { color: var(--accent-lime); }
.sidebar-body { flex: 1; overflow-y: auto; padding: 30px; }
.loading-state { display: flex; flex-direction: column; align-items: center; justify-content: center; height: 100%; color: var(--text-sub); gap: 20px; }
.markdown-content, .text-content { line-height: 1.8; color: var(--text-main); font-size: 0.95rem; }
.text-content pre { white-space: pre-wrap; font-family: monospace; background: #000; padding: 15px; border-radius: 8px; border: 1px solid var(--border-tech); color: #ccc; }
.markdown-content h1, .markdown-content h2, .markdown-content h3 { color: var(--accent-lime); margin-top: 1.5em; margin-bottom: 0.5em; font-family: 'Space Grotesk', sans-serif; }
.markdown-content h1 { border-bottom: 1px solid var(--border-tech); padding-bottom: 10px; }
.markdown-content ul { padding-left: 20px; }
.markdown-content li { margin-bottom: 8px; color: #d4d4d8; }
.markdown-content strong { color: var(--accent-lime); font-weight: 700; }
.markdown-content p { margin-bottom: 1em; }

/* 登录框 */
.auth-backdrop { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: rgba(0,0,0,0.8); backdrop-filter: blur(5px); z-index: 2000; display: flex; justify-content: center; align-items: center; }
.auth-panel { width: 400px; max-width: 90vw; background: var(--bg-card); border: 1px solid var(--border-tech); border-top: 2px solid var(--accent-lime); box-shadow: 0 20px 50px rgba(0,0,0,0.8); display: flex; flex-direction: column; animation: slideUpFade 0.3s forwards; }
.auth-header { padding: 20px; border-bottom: 1px solid var(--border-tech); display: flex; justify-content: space-between; align-items: center; background: rgba(11,12,16,0.9); }
.auth-title { font-family: 'Noto Sans SC', sans-serif; font-size: 1.2rem; color: var(--text-main); font-weight: 700; letter-spacing: 1px; }
.auth-body { padding: 30px; }
.input-group { margin-bottom: 20px; }
.input-group label { display: block; font-family: 'Noto Sans SC', monospace; color: var(--text-sub); font-size: 0.75rem; margin-bottom: 8px; letter-spacing: 1px; }
.input-group input { width: 100%; background: #000; border: 1px solid var(--border-tech); padding: 12px; color: var(--text-main); font-family: monospace; font-size: 1rem; outline: none; transition: all 0.3s; }
.input-group input:focus { border-color: var(--accent-lime); box-shadow: 0 0 10px rgba(34, 211, 238, 0.22); }
.cyber-btn { width: 100%; background: var(--text-main); color: var(--bg-deep); border: none; padding: 12px; font-weight: 700; font-family: 'Noto Sans SC', sans-serif; cursor: pointer; transition: all 0.3s; clip-path: polygon(5% 0%, 100% 0%, 95% 100%, 0% 100%); margin-bottom: 20px; }
.cyber-btn:hover:not(:disabled) { background: var(--accent-lime); color: var(--text-inverse); box-shadow: 0 0 20px rgba(34, 211, 238, 0.34); }
.cyber-btn:disabled { opacity: 0.5; cursor: not-allowed; }
.auth-toggle { text-align: center; font-size: 0.85rem; font-family: 'Noto Sans SC', sans-serif; color: var(--text-sub); }
.toggle-link { background: none; border: none; color: var(--accent-lime); cursor: pointer; font-weight: 700; margin-left: 5px; text-decoration: underline; }
.toggle-link:hover { color: #fff; }
.auth-msg { margin-top: 15px; text-align: center; font-family: 'Noto Sans SC', monospace; font-size: 0.8rem; color: var(--accent-lime); }
.auth-msg.error { color: #ff4757; }

/* 删除按钮 */
.delete-btn {
  position: absolute; top: 10px; right: 10px; background: transparent; border: none;
  color: #71757a; cursor: pointer; opacity: 0; transition: all 0.3s ease; z-index: 10; padding: 5px;
}
.project-card:hover .delete-btn { opacity: 1; }
.delete-btn:hover { color: #ff4757; transform: scale(1.2) rotate(90deg); }

/* Workbench refactor */
.navbar { padding: 0.9rem 0; }
.ambient-glow { background: radial-gradient(circle, rgba(34, 211, 238, 0.1) 0%, rgba(7, 11, 20, 0) 70%); }
.nav-content { align-items: center; gap: 24px; }
.brand { align-items: center; gap: 12px; min-width: 0; }
.brand-mark {
  width: 42px; height: 42px; border: 1px solid var(--accent-lime); border-radius: 8px;
  display: grid; place-items: center; color: var(--accent-lime); font-weight: 800;
  background: rgba(34, 211, 238, 0.08); box-shadow: inset 0 0 18px rgba(124, 58, 237, 0.12);
}
.brand-copy { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.brand-title { font-family: 'Noto Sans SC', sans-serif; font-size: 1.1rem; font-weight: 800; color: var(--text-main); }
.brand-subtitle { font-family: 'Noto Sans SC', sans-serif; font-size: 0.78rem; color: var(--text-sub); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.main-container { max-width: 1440px; padding: 2rem; }
.console-intro {
  display: flex; align-items: end; justify-content: space-between; gap: 20px;
  margin-bottom: 18px; padding: 22px 0 12px; border-bottom: 1px solid var(--border-tech);
}
.eyebrow, .panel-kicker {
  color: var(--accent-lime); font-family: monospace; font-size: 0.72rem;
  letter-spacing: 1px; margin-bottom: 6px;
}
.console-intro h1 { font-size: clamp(1.5rem, 3vw, 2.4rem); line-height: 1.15; font-family: 'Noto Sans SC', sans-serif; }
.intro-subtitle { margin-top: 8px; color: var(--text-sub); font-size: 0.95rem; font-family: 'Noto Sans SC', sans-serif; }
.intro-metrics { display: flex; gap: 10px; flex-wrap: wrap; justify-content: flex-end; }
.intro-metrics span {
  border: 1px solid var(--border-tech); border-radius: 6px; padding: 8px 12px;
  background: rgba(15, 23, 42, 0.72); color: var(--text-sub); font-size: 0.82rem;
}

.workspace-tabs {
  display: inline-grid; grid-template-columns: repeat(2, minmax(120px, 1fr));
  gap: 6px; padding: 6px; margin: 0 0 18px; border: 1px solid var(--border-tech);
  border-radius: 8px; background: rgba(8, 15, 30, 0.72);
}
.workspace-tab {
  border: 1px solid transparent; border-radius: 6px; background: transparent; color: var(--text-sub);
  padding: 10px 16px; cursor: pointer; font-family: 'Noto Sans SC', sans-serif; font-weight: 700;
  transition: all 0.2s ease;
}
.workspace-tab:hover { color: var(--accent-lime); }
.workspace-tab.active {
  color: var(--text-inverse); background: var(--accent-lime); border-color: var(--accent-lime);
  box-shadow: 0 0 18px rgba(34, 211, 238, 0.2);
}

.workbench-shell { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 20px; align-items: stretch; }
.workbench-main { min-width: 0; display: flex; flex-direction: column; gap: 20px; align-self: stretch; }
.video-workspace { display: flex; flex-direction: column; gap: 20px; }
.hero-section, .workspace-section, .rag-section {
  margin: 0; text-align: left; opacity: 1; animation: none;
  background: rgba(15, 23, 42, 0.74); border: 1px solid var(--border-tech);
  border-radius: 8px; padding: 20px; box-shadow: var(--shadow-float);
}
.section-header {
  justify-content: space-between; align-items: flex-start; margin-bottom: 16px;
  border-bottom: 1px solid var(--border-tech); padding-bottom: 12px;
}
.section-header h3 { font-size: 1.2rem; font-family: 'Noto Sans SC', sans-serif; }
.upload-wrapper { max-width: none; opacity: 1; animation: none; }
.upload-magnet { height: 220px; border-radius: 8px; border-width: 1px; }
.upload-magnet:hover { transform: none; }
.magnet-title { font-family: 'Space Grotesk', 'Noto Sans SC', sans-serif; font-size: 1.1rem; }
.card-grid { grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); }
.project-card, .rag-panel, .rag-doc-item, .rag-source-item { border-radius: 8px; }
.empty-state {
  border: 1px dashed var(--border-tech); border-radius: 8px; padding: 24px;
  color: var(--text-sub); text-align: center; background: rgba(8, 15, 30, 0.5);
}
.notification-bar { display: block; width: fit-content; margin: 0 0 18px; clip-path: none; border-radius: 6px; }
.rag-section { margin-top: 0; }
.rag-layout { grid-template-columns: minmax(0, 0.95fr) minmax(0, 1.05fr); }
.rag-answer, .rag-sources { background: rgba(8, 15, 30, 0.5); border: 1px solid var(--border-tech); border-radius: 8px; padding: 14px; }

.result-panel {
  min-height: 0; height: auto; align-self: stretch;
  display: flex; flex-direction: column; overflow: hidden;
  background: rgba(10, 18, 34, 0.94); border: 1px solid var(--border-tech);
  border-top: 2px solid var(--accent-lime); border-radius: 8px; box-shadow: var(--shadow-float);
}
.result-panel.has-result { border-color: rgba(34, 211, 238, 0.58); }
.result-panel .sidebar-header { padding: 16px 18px; }
.result-panel .sidebar-title { font-size: 1rem; min-width: 0; }
.result-panel .sidebar-body { padding: 18px; }
.result-empty {
  min-height: 320px; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 14px; color: var(--text-sub); text-align: center; line-height: 1.7;
}
.result-empty-icon {
  width: 64px; height: 64px; border-radius: 12px; display: grid; place-items: center;
  border: 1px solid var(--accent-lime); color: var(--accent-lime); font-weight: 800;
  background: rgba(34, 211, 238, 0.08);
}
.result-sources { margin-top: 18px; padding-top: 16px; border-top: 1px solid var(--border-tech); }

.status-dot { background: var(--accent-success); }
.status-pill.is-active .status-dot { animation: pulse-success 1.5s infinite alternate; }
.status-indicator.completed,
.rag-doc-status.ready {
  color: var(--accent-success);
  border-color: var(--accent-success);
  background: rgba(90, 242, 141, 0.1);
}
.notification-bar { background: var(--accent-success); }
.auth-msg { color: var(--accent-success); }

@media (max-width: 1100px) {
  .workbench-shell { grid-template-columns: 1fr; }
  .result-panel { position: relative; top: auto; min-height: 360px; max-height: none; }
}

@media (max-width: 760px) {
  .nav-content, .console-intro { flex-direction: column; align-items: stretch; }
  .nav-controls, .intro-metrics { justify-content: flex-start; }
  .main-container { padding: 1rem; }
  .workspace-tabs { display: grid; width: 100%; }
  .upload-magnet { height: auto; min-height: 320px; }
  .split-container { flex-direction: column; }
  .skew-pane, .pane-content, .split-gap { transform: none; }
  .pane-local, .pane-url { margin: 0; padding: 18px; border-right: none; }
  .pane-local { border-bottom: 1px solid var(--border-tech); }
  .action-dock { grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
  .dock-item { padding: 10px 6px; font-size: 0.78rem; gap: 5px; }
}

@keyframes spin { to { transform: rotate(360deg); } }
@keyframes slideUpFade { from { opacity: 0; transform: translateY(40px); } to { opacity: 1; transform: translateY(0); } }
@keyframes pulse-lime { 0% { opacity: 0.5; box-shadow: 0 0 5px var(--accent-lime); } 100% { opacity: 1; box-shadow: 0 0 15px var(--accent-lime); } }
@keyframes pulse-success { 0% { opacity: 0.55; box-shadow: 0 0 5px var(--accent-success); } 100% { opacity: 1; box-shadow: 0 0 15px var(--accent-success); } }
@keyframes blink { 50% { opacity: 0.5; } }
</style>
