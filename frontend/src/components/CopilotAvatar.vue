<template>
  <!-- 助手形象：日系漫画 Q 版女孩「小维」 -->
  <span class="copilot-avatar" :class="'st-' + state" :style="{ width: size + 'px', height: size + 'px' }">
    <svg viewBox="0 0 120 140" xmlns="http://www.w3.org/2000/svg" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient :id="hairId" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stop-color="#9db2ff" />
          <stop offset="45%" stop-color="#7d86f7" />
          <stop offset="100%" stop-color="#a565e8" />
        </linearGradient>
        <linearGradient :id="hairDeepId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#7d8bf5" />
          <stop offset="100%" stop-color="#8f5ad8" />
        </linearGradient>
        <linearGradient :id="eyeId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#3948b8" />
          <stop offset="52%" stop-color="#7a63e8" />
          <stop offset="100%" stop-color="#cdb6ff" />
        </linearGradient>
        <linearGradient :id="skinId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#fff0e7" />
          <stop offset="100%" stop-color="#ffd8c6" />
        </linearGradient>
        <linearGradient :id="clothId" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stop-color="#ffffff" />
          <stop offset="100%" stop-color="#e3eaff" />
        </linearGradient>
        <linearGradient :id="ribbonId" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0%" stop-color="#ffa9cd" />
          <stop offset="100%" stop-color="#e8669f" />
        </linearGradient>
        <linearGradient :id="skirtId" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stop-color="#707be6" />
          <stop offset="100%" stop-color="#4a53b8" />
        </linearGradient>
        <radialGradient :id="haloId" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#9db2ff" stop-opacity=".28" />
          <stop offset="65%" stop-color="#c08cff" stop-opacity=".10" />
          <stop offset="100%" stop-color="#c08cff" stop-opacity="0" />
        </radialGradient>
        <radialGradient :id="blushId" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stop-color="#ff8fb5" stop-opacity=".55" />
          <stop offset="100%" stop-color="#ff8fb5" stop-opacity="0" />
        </radialGradient>
        <pattern :id="dotId" width="6" height="6" patternUnits="userSpaceOnUse">
          <circle cx="1.2" cy="1.2" r=".7" fill="#aab6ff" opacity=".55" />
        </pattern>
      </defs>

      <!-- 柔和光晕 -->
      <circle class="halo" cx="60" cy="66" r="55" :fill="haloUrl" />
      <circle class="halo-dots" cx="60" cy="66" r="53" :fill="dotUrl" />
      <!-- 地面投影（不随身体跳动） -->
      <ellipse class="shadow" cx="60" cy="137.6" rx="19" ry="2.4" fill="#7d86b8" opacity=".2" />

      <g class="bob">
        <!-- 后层双马尾 -->
        <g class="tail tail-l">
          <path d="M25 55 C9 59 5 79 11 94 C14 102 23 103 26 95 C29 85 21 73 31 63 Z"
                :fill="hairDeepUrl" stroke="#5b60c8" stroke-width="1.1" stroke-linejoin="round" />
          <path d="M22 63 C15 69 12 80 16 90" fill="none" stroke="#d7e0ff" stroke-width="1.7" stroke-linecap="round" opacity=".75" />
          <path d="M23 55 L18 49 L29 51 Z" :fill="ribbonUrl" />
          <circle cx="24.5" cy="54.5" r="2.2" fill="#ffd3e5" />
        </g>
        <g class="tail tail-r">
          <path d="M95 55 C111 59 115 79 109 94 C106 102 97 103 94 95 C91 85 99 73 89 63 Z"
                :fill="hairDeepUrl" stroke="#5b60c8" stroke-width="1.1" stroke-linejoin="round" />
          <path d="M98 63 C105 69 108 80 104 90" fill="none" stroke="#d7e0ff" stroke-width="1.7" stroke-linecap="round" opacity=".75" />
          <path d="M97 55 L102 49 L91 51 Z" :fill="ribbonUrl" />
          <circle cx="95.5" cy="54.5" r="2.2" fill="#ffd3e5" />
        </g>

        <!-- 身体（全身） -->
        <g class="body">
          <!-- 后侧长发 -->
          <path d="M28 56 C24 34 40 20 60 20 C80 20 96 34 92 56 C95 73 92 85 88 92 C84 81 82 68 82 58 C80 46 73 40 60 40 C47 40 40 46 38 58 C38 68 36 81 32 92 C28 85 25 73 28 56 Z"
                :fill="hairUrl" stroke="#5b60c8" stroke-width="1" stroke-linejoin="round" />

          <!-- 地面投影（不随身体跳动） -->

          <!-- 双腿（裙子会盖住上沿） -->
          <g class="leg leg-l">
            <path d="M48 108 C47 118 47 127 46 133 C50 135.5 54 135.5 55 132.5 C55 126 55 117 55 108 Z"
                  :fill="skinUrl" stroke="#e6ab95" stroke-width=".9" stroke-linejoin="round" />
            <path d="M46.3 125 C48.5 126.5 52.8 126.5 55 125 L54.6 132 C52 133.5 49 133.5 46.7 132 Z"
                  fill="#f8faff" stroke="#d7ddf7" stroke-width=".7" />
            <path d="M46.6 125.5 C49 127 52.5 127 54.7 125.6" stroke="#8fa8ff" stroke-width="1" fill="none" />
            <path d="M45.2 132.5 C44.7 134.2 46.3 136.8 49.3 137.2 C52.2 137.6 55.2 136.4 55.6 134.4 L55.2 131.6 C51.9 133.1 48.2 133.2 45.2 132.5 Z"
                  fill="#4b5bd6" stroke="#3a48b8" stroke-width=".8" stroke-linejoin="round" />
            <path d="M45.6 135.8 C49 137.2 53.2 137 55.5 135.2" stroke="#e8ecff" stroke-width="1.3" fill="none" stroke-linecap="round" />
          </g>
          <g class="leg leg-r">
            <path d="M72 108 C73 118 73 127 74 133 C70 135.5 66 135.5 65 132.5 C65 126 65 117 65 108 Z"
                  :fill="skinUrl" stroke="#e6ab95" stroke-width=".9" stroke-linejoin="round" />
            <path d="M73.7 125 C71.5 126.5 67.2 126.5 65 125 L65.4 132 C68 133.5 71 133.5 73.3 132 Z"
                  fill="#f8faff" stroke="#d7ddf7" stroke-width=".7" />
            <path d="M73.4 125.5 C71 127 67.5 127 65.3 125.6" stroke="#8fa8ff" stroke-width="1" fill="none" />
            <path d="M74.8 132.5 C75.3 134.2 73.7 136.8 70.7 137.2 C67.8 137.6 64.8 136.4 64.4 134.4 L64.8 131.6 C68.1 133.1 71.8 133.2 74.8 132.5 Z"
                  fill="#4b5bd6" stroke="#3a48b8" stroke-width=".8" stroke-linejoin="round" />
            <path d="M74.4 135.8 C71 137.2 66.8 137 64.5 135.2" stroke="#e8ecff" stroke-width="1.3" fill="none" stroke-linecap="round" />
          </g>

          <!-- 脖子 -->
          <rect x="53" y="82" width="14" height="15" rx="6.4" :fill="skinUrl" stroke="#e6ab95" stroke-width=".8" />

          <!-- 水手服上衣 -->
          <path d="M36 112 C36 99 46 93 60 93 C74 93 84 99 84 112 Z"
                :fill="clothUrl" stroke="#bcc8ff" stroke-width="1.2" stroke-linejoin="round" />
          <!-- 水手领 -->
          <path d="M40 96 C47 92.5 53 93.5 60 100 C67 93.5 73 92.5 80 96 L83 104 C75 99.5 68 101 60 107 C52 101 45 99.5 37 104 Z"
                fill="#6373d8" opacity=".92" />
          <path d="M43 99 C48 97 53 98 60 103 C67 98 72 97 77 99" fill="none" stroke="#ffffff" stroke-width="1.2" opacity=".8" />

          <!-- 百褶裙 -->
          <path class="skirt" d="M35 108 L85 108 L92 126 C78 130 42 130 28 126 Z"
                :fill="skirtUrl" stroke="#414aa0" stroke-width="1" stroke-linejoin="round" />
          <path d="M42 109 L38 126 M50 109 L48 127 M58 109 L58 128 M66 109 L68 127 M74 109 L78 126"
                stroke="#a7b0ff" stroke-width="1" opacity=".55" />
          <path d="M28.5 125.4 C42 129 78 129 91.5 125.4 L92 127 C78 131 42 131 28 127 Z" fill="#eef1ff" />
          <path d="M35 108 L85 108 L85.4 110.2 L34.6 110.2 Z" fill="#8b94f5" opacity=".95" />

          <!-- 领结 -->
          <path d="M56 103 L60 107 L64 103 L67 110 L60 113 L53 110 Z" :fill="ribbonUrl" />
          <circle cx="60" cy="108" r="2.3" fill="#ffd3e5" />
          <!-- 胸牌 AI 核心 -->
          <circle cx="45" cy="101" r="2.2" fill="#8fa8ff" opacity=".9" />
          <circle class="core-glow" cx="45" cy="101" r="4.3" fill="none" stroke="#9db2ff" stroke-width="1" opacity=".5" />

          <!-- 左手臂（自然下垂） -->
          <g class="arm-l-idle">
            <path d="M37 98 C31 101 28.5 106 28.5 112 L34.5 114 C35 108 38 104 42 102 Z"
                  :fill="clothUrl" stroke="#bcc8ff" stroke-width="1.1" stroke-linejoin="round" />
            <ellipse cx="31.5" cy="113.5" rx="4.2" ry="3.6" :fill="skinUrl" stroke="#e6ab95" stroke-width=".9" />
          </g>
          <!-- 右手臂（挥手） -->
          <g class="arm-r-wave">
            <path d="M83 99 C89 95 92 89 92 84 L98 86 C97 92 94 98 89 102 Z"
                  :fill="clothUrl" stroke="#bcc8ff" stroke-width="1.1" stroke-linejoin="round" />
            <g class="hand-wave">
              <path d="M92 87 C95.5 83.5 98.5 78.5 100.5 74.5 L104.5 76.5 C103 81 100 86 96.5 90 Z"
                    :fill="skinUrl" stroke="#e6ab95" stroke-width=".9" stroke-linejoin="round" />
              <ellipse cx="106" cy="70" rx="4.1" ry="4.6" :fill="skinUrl" stroke="#e6ab95" stroke-width=".9" />
              <path d="M103.5 67 L106 64 M105.5 67.5 L108.5 64.5 M106.5 69 L110 67.2"
                    stroke="#e6ab95" stroke-width=".9" stroke-linecap="round" />
            </g>
          </g>
          <!-- 右手臂（放下，思考时使用） -->
          <g class="arm-r-idle">
            <path d="M83 99 C89 102 91.5 107 91.5 112 L85.5 114 C85 108 82 104 78 102 Z"
                  :fill="clothUrl" stroke="#bcc8ff" stroke-width="1.1" stroke-linejoin="round" />
            <ellipse cx="88.5" cy="113.5" rx="4.2" ry="3.6" :fill="skinUrl" stroke="#e6ab95" stroke-width=".9" />
          </g>
        </g>

        <!-- 头部 -->
        <g class="head">
          <!-- 侧发 -->
          <path d="M30 47 C26 61 27 75 32 87 C35 92 40 90 38.5 84 C35 74 34 62 37 49 Z"
                :fill="hairUrl" stroke="#5b60c8" stroke-width="1" stroke-linejoin="round" />
          <path d="M90 47 C94 61 93 75 88 87 C85 92 80 90 81.5 84 C85 74 86 62 83 49 Z"
                :fill="hairUrl" stroke="#5b60c8" stroke-width="1" stroke-linejoin="round" />

          <!-- 脸 -->
          <path d="M60 30 C79 30 91 43 91 60 C91 79 77 89 60 89 C43 89 29 79 29 60 C29 43 41 30 60 30 Z"
                :fill="skinUrl" stroke="#e3a891" stroke-width=".9" />
          <ellipse cx="29.5" cy="64" rx="4" ry="6" :fill="skinUrl" stroke="#e6ab95" stroke-width=".8" />
          <ellipse cx="90.5" cy="64" rx="4" ry="6" :fill="skinUrl" stroke="#e6ab95" stroke-width=".8" />

          <!-- 刘海 -->
          <path d="M28 58 C25 38 40 22 60 22 C80 22 95 38 92 58 C89 51 85 48 81 47 C79 52 76 54 72 55 C72 49 69 45 64 43 C61 48 56 51 50 52 C49 46 45 43 40 42 C38 49 34 54 28 58 Z"
                :fill="hairUrl" stroke="#5b60c8" stroke-width="1.1" stroke-linejoin="round" />
          <!-- 发丝高光 -->
          <path d="M39 33 C46 27 55 25 62 26" fill="none" stroke="#e3e9ff" stroke-width="2.2" stroke-linecap="round" opacity=".85" />
          <path d="M69 28 C75 31 79 36 81 41" fill="none" stroke="#c8d3ff" stroke-width="1.3" stroke-linecap="round" opacity=".75" />
          <!-- 呆毛 -->
          <path class="ahoge" d="M60 22 C58 14 64 8 71 7" fill="none" stroke="#9db2ff" stroke-width="3.2" stroke-linecap="round" />

          <!-- 耳机 -->
          <path d="M28 56 C30 26 90 26 92 56" fill="none" stroke="#4152c9" stroke-width="4" stroke-linecap="round" />
          <rect x="20" y="55" width="12" height="20" rx="6" fill="#4b5bd6" />
          <rect x="22.5" y="59" width="6" height="12" rx="3" fill="#8b99ff" />
          <rect x="88" y="55" width="12" height="20" rx="6" fill="#4b5bd6" />
          <rect x="90.5" y="59" width="6" height="12" rx="3" fill="#8b99ff" />
          <path d="M94 71 C95 81 86 85 79 85" fill="none" stroke="#4b5bd6" stroke-width="2.4" stroke-linecap="round" />
          <circle cx="77.5" cy="85" r="3.2" fill="#ff9ec4" />
          <circle class="headset-led" cx="26" cy="59" r="1.6" fill="#7dffe0" />

          <!-- 眉毛 -->
          <path class="brow brow-l" d="M42.5 57.2 C46 54.8 50.5 55 53 56.6" fill="none" stroke="#a08cb8" stroke-width="1.4" stroke-linecap="round" />
          <path class="brow brow-r" d="M67 56.6 C69.5 55 74 54.8 77.5 57.2" fill="none" stroke="#a08cb8" stroke-width="1.4" stroke-linecap="round" />

          <!-- 眼睛 -->
          <g class="eye eye-l">
            <ellipse cx="48.5" cy="67.5" rx="7" ry="8.7" fill="#fff" />
            <g class="iris">
              <circle cx="48.5" cy="68" r="5.7" :fill="eyeUrl" />
              <circle cx="48.5" cy="68.2" r="2.5" fill="#241d44" />
              <circle cx="46.4" cy="64.8" r="2.1" fill="#ffffff" />
              <circle cx="50.9" cy="71.2" r="1" fill="#ffffff" opacity=".9" />
              <path d="M44 63.2 L44.8 65.6 L47.2 66.4 L44.8 67.2 L44 69.6 L43.2 67.2 L40.8 66.4 L43.2 65.6 Z" fill="#fff" opacity=".85" />
            </g>
            <path d="M41 61.8 C44 58.8 52.6 58.6 56 61.2" fill="none" stroke="#4a3b67" stroke-width="1.8" stroke-linecap="round" />
            <path d="M41.5 74.4 C44.5 76 52.5 76 55.5 74.4" fill="none" stroke="#b98b8b" stroke-width=".8" stroke-linecap="round" opacity=".6" />
            <path d="M41.2 61.5 L38.6 60.2" fill="none" stroke="#4a3b67" stroke-width="1.5" stroke-linecap="round" />
          </g>
          <g class="eye eye-r">
            <ellipse cx="71.5" cy="67.5" rx="7" ry="8.7" fill="#fff" />
            <g class="iris">
              <circle cx="71.5" cy="68" r="5.7" :fill="eyeUrl" />
              <circle cx="71.5" cy="68.2" r="2.5" fill="#241d44" />
              <circle cx="69.4" cy="64.8" r="2.1" fill="#ffffff" />
              <circle cx="73.9" cy="71.2" r="1" fill="#ffffff" opacity=".9" />
              <path d="M75.2 63.2 L76 65.6 L78.4 66.4 L76 67.2 L75.2 69.6 L74.4 67.2 L72 66.4 L74.4 65.6 Z" fill="#fff" opacity=".85" />
            </g>
            <path d="M64 61.2 C67.4 58.6 76 58.8 79 61.8" fill="none" stroke="#4a3b67" stroke-width="1.8" stroke-linecap="round" />
            <path d="M64.5 74.4 C67.5 76 75.5 76 78.5 74.4" fill="none" stroke="#b98b8b" stroke-width=".8" stroke-linecap="round" opacity=".6" />
            <path d="M78.8 61.5 L81.4 60.2" fill="none" stroke="#4a3b67" stroke-width="1.5" stroke-linecap="round" />
          </g>

          <!-- 腮红 -->
          <ellipse class="blush" cx="38" cy="75.5" rx="6.2" ry="3" :fill="blushUrl" />
          <ellipse class="blush" cx="82" cy="75.5" rx="6.2" ry="3" :fill="blushUrl" />
          <path d="M35.5 74 L38 77 M38.5 73.6 L41 76.6" stroke="#ff9fb5" stroke-width="1" stroke-linecap="round" opacity=".55" />
          <path d="M79 73.6 L81.5 76.6 M82 74 L84.5 77" stroke="#ff9fb5" stroke-width="1" stroke-linecap="round" opacity=".55" />

          <!-- 嘴 -->
          <path class="mouth-idle" d="M55.6 79.4 C58 83.2 62 83.2 64.4 79.4"
                fill="none" stroke="#cf6482" stroke-width="1.9" stroke-linecap="round" />
          <ellipse class="mouth-think" cx="60" cy="80.6" rx="2.2" ry="1.7" fill="#d8738c" />
          <ellipse class="mouth-talk" cx="60" cy="80.8" rx="3.4" ry="2.4" fill="#d8738c" />
        </g>

        <!-- 思考手势（左手托腮） -->
        <g class="arm-think">
          <path d="M37 100 C31 96 30 89 36 85 L41 88 C37 92 38 96 42 100 Z"
                :fill="clothUrl" stroke="#bcc8ff" stroke-width="1.1" stroke-linejoin="round" />
          <path d="M37.5 88 C39 87 40.5 86 41.5 84.8" fill="none" stroke="#e6ab95" stroke-width="3.2" stroke-linecap="round" />
          <ellipse cx="41.5" cy="84.5" rx="4.8" ry="5.4" :fill="skinUrl" stroke="#e6ab95" stroke-width=".9" transform="rotate(-18 41.5 84.5)" />
          <path d="M38.2 82.4 L42 81.2 M38.6 85.4 L42.4 84.2" stroke="#e6ab95" stroke-width=".9" stroke-linecap="round" />
        </g>

        <!-- 思考气泡 -->
        <g class="think-dots">
          <circle cx="94" cy="27" r="2.4" fill="#8b99ff" />
          <circle cx="103" cy="19.5" r="3" fill="#9db2ff" />
          <circle cx="113" cy="11.5" r="3.6" fill="#a565e8" />
        </g>

        <!-- 漫画星屑 -->
        <g class="sparkles">
          <g transform="translate(100 31)">
            <path class="spark s1" d="M0 -5 L1.2 -1.2 L5 0 L1.2 1.2 L0 5 L-1.2 1.2 L-5 0 L-1.2 -1.2 Z" fill="#c9d6ff" />
          </g>
          <g transform="translate(18 39)">
            <path class="spark s2" d="M0 -3.6 L.9 -.9 L3.6 0 L.9 .9 L0 3.6 L-.9 .9 L-3.6 0 L-.9 -.9 Z" fill="#ffc2e0" />
          </g>
          <g transform="translate(101 82)">
            <path class="spark s3" d="M0 -2.8 L.7 -.7 L2.8 0 L.7 .7 L0 2.8 L-.7 .7 L-2.8 0 L-.7 -.7 Z" fill="#c7b4ff" />
          </g>
        </g>
      </g>
    </svg>
  </span>
</template>

<script setup lang="ts">
import { computed } from 'vue'

type AvatarState = 'idle' | 'thinking' | 'talking'

const props = withDefaults(defineProps<{ state?: AvatarState; size?: number }>(), {
  state: 'idle',
  size: 56
})

// SVG 渐变/pattern id 必须全局唯一，避免同页面多个实例互相覆盖
let avatarSeq = 0
const ns = 'copilot-avatar-' + (++avatarSeq) + '-' + Math.random().toString(36).slice(2, 6)
const hairId = ns + '-hair'
const hairDeepId = ns + '-hair-deep'
const eyeId = ns + '-eye'
const skinId = ns + '-skin'
const clothId = ns + '-cloth'
const ribbonId = ns + '-ribbon'
const skirtId = ns + '-skirt'
const haloId = ns + '-halo'
const blushId = ns + '-blush'
const dotId = ns + '-dots'

const hairUrl = computed(() => `url(#${hairId})`)
const hairDeepUrl = computed(() => `url(#${hairDeepId})`)
const eyeUrl = computed(() => `url(#${eyeId})`)
const skinUrl = computed(() => `url(#${skinId})`)
const clothUrl = computed(() => `url(#${clothId})`)
const ribbonUrl = computed(() => `url(#${ribbonId})`)
const skirtUrl = computed(() => `url(#${skirtId})`)
const haloUrl = computed(() => `url(#${haloId})`)
const blushUrl = computed(() => `url(#${blushId})`)
const dotUrl = computed(() => `url(#${dotId})`)

const state = computed<AvatarState>(() => props.state)
const size = computed(() => props.size)
</script>

<style scoped>
.copilot-avatar { display: inline-flex; align-items: center; justify-content: center; line-height: 0; }
.copilot-avatar svg { width: 100%; height: 100%; display: block; overflow: visible; }

/* 整体呼吸 + 头部轻晃 */
.bob { transform-box: fill-box; transform-origin: 50% 100%; animation: avatar-bob 5.2s ease-in-out infinite; }
.shadow { transform-box: fill-box; transform-origin: center; animation: shadow-hop 5.2s ease-in-out infinite; }
.head { transform-box: fill-box; transform-origin: 60px 88px; animation: avatar-sway 5.6s ease-in-out infinite; }
.st-thinking .head { animation: avatar-think 2.6s ease-in-out infinite; }
.st-talking .bob { animation: avatar-talk-bob 1.4s ease-in-out infinite; }

/* 光晕 */
.halo { transform-box: fill-box; transform-origin: center; animation: avatar-halo 4.2s ease-in-out infinite; }
.halo-dots { opacity: .16; transform-box: fill-box; transform-origin: center; animation: avatar-halo 6s ease-in-out infinite reverse; }

/* 双马尾摆动 */
.tail { transform-box: fill-box; }
.tail-l { transform-origin: 88% 4%; animation: tail-l 4.8s ease-in-out infinite; }
.tail-r { transform-origin: 12% 4%; animation: tail-r 4.8s ease-in-out infinite; }
.st-thinking .tail-l { animation-duration: 3.2s; }
.st-thinking .tail-r { animation-duration: 3.2s; }
.st-talking .tail-l, .st-talking .tail-r { animation-duration: 2.4s; }

/* 呆毛 */
.ahoge { transform-box: fill-box; transform-origin: 50% 100%; animation: avatar-ahoge 2.8s ease-in-out infinite; }
.st-thinking .ahoge { animation-duration: 1.8s; }

/* 眼睛：眨眼 / 偶尔单眼 wink / 左右看 */
.eye { transform-box: fill-box; transform-origin: center; }
.st-idle .eye-l { animation: blink-l 5.6s ease-in-out infinite; }
.st-idle .eye-r { animation: blink-r 5.6s ease-in-out infinite; }
.st-thinking .eye { animation: blink-think 7s ease-in-out infinite; }
.iris { transform-box: fill-box; transform-origin: center; }
.st-idle .iris { animation: glance 8.5s ease-in-out infinite; }
.st-thinking .iris { transform: translateY(-1.4px); }
.st-talking .iris { transform: scale(1.05); }
.brow { transform-box: fill-box; transform-origin: center; transition: transform .2s; }
.st-thinking .brow-l { transform: translateY(-.7px) rotate(-3deg); }
.st-thinking .brow-r { transform: translateY(-.9px) rotate(3deg); }

/* 嘴型 */
.mouth-think, .mouth-talk { display: none; transform-box: fill-box; transform-origin: center; }
.st-thinking .mouth-idle { display: none; }
.st-thinking .mouth-think { display: block; animation: mouth-think 2.4s ease-in-out infinite; }
.st-talking .mouth-idle, .st-talking .mouth-think { display: none; }
.st-talking .mouth-talk { display: block; animation: avatar-talk .28s ease-in-out infinite alternate; }

/* 手臂 / 腿：待机挥手、思考托腮、脚点地 */
.arm-r-idle, .arm-think { display: none; }
.st-thinking .arm-l-idle, .st-thinking .arm-r-wave { display: none; }
.st-thinking .arm-r-idle, .st-thinking .arm-think { display: block; }
.arm-l-idle { transform-box: fill-box; transform-origin: 85% 0%; animation: arm-swing 4.4s ease-in-out infinite; }
.arm-r-wave { transform-box: fill-box; transform-origin: 2% 96%; animation: arm-wave 3.4s ease-in-out infinite; }
.hand-wave { transform-box: fill-box; transform-origin: 2% 96%; animation: hand-wave 1.05s ease-in-out infinite alternate; }
.arm-think { animation: hand-bob 2.6s ease-in-out infinite; transform-origin: 40% 90%; transform-box: fill-box; }
.leg { transform-box: fill-box; transform-origin: 50% 0%; }
.st-thinking .leg-r { animation: foot-tap 1.1s ease-in-out infinite; }
.st-talking .arm-r-wave { animation-duration: 1.9s; }
.st-talking .hand-wave { animation-duration: .68s; }
.st-talking .arm-l-idle { animation-duration: 2.4s; }

/* 腮红呼吸 */
.blush { transition: opacity .3s; }
.st-thinking .blush, .st-talking .blush { opacity: 1; }
.copilot-avatar:hover .blush { opacity: 1.15; }
.copilot-avatar:hover .sparkles { opacity: 1; }

/* AI 核心 / 耳机灯 */
.core-glow { transform-box: fill-box; transform-origin: center; animation: core-pulse 2.4s ease-in-out infinite; }
.headset-led { animation: led-blink 2.2s ease-in-out infinite; }

/* 思考气泡与星屑 */
.think-dots { opacity: 0; transition: opacity .2s; }
.st-thinking .think-dots { opacity: 1; }
.think-dots circle { transform-box: fill-box; transform-origin: center; animation: thought-rise 2s ease-in-out infinite; }
.think-dots circle:nth-child(2) { animation-delay: .25s; }
.think-dots circle:nth-child(3) { animation-delay: .5s; }
.sparkles { opacity: .75; transition: opacity .25s; }
.spark { transform-box: fill-box; transform-origin: center; animation: sparkle 3s ease-in-out infinite; }
.spark.s2 { animation-delay: .8s; }
.spark.s3 { animation-delay: 1.6s; }
.st-thinking .spark { animation-duration: 1.8s; }
.st-talking .sparkles { opacity: 1; }

@keyframes avatar-bob {
  0%, 50%, 100% { transform: translateY(0); }
  56% { transform: translateY(-4px); }
  62% { transform: translateY(0); }
  67% { transform: translateY(-1.2px); }
  72% { transform: translateY(0); }
}
@keyframes shadow-hop {
  0%, 50%, 64%, 100% { transform: scaleX(1); opacity: .2; }
  56% { transform: scaleX(.84); opacity: .11; }
  60% { transform: scaleX(.92); opacity: .15; }
}
@keyframes arm-swing {
  0%, 100% { transform: rotate(-1.6deg); }
  50% { transform: rotate(1.6deg); }
}
@keyframes arm-wave {
  0%, 52%, 100% { transform: rotate(0deg); }
  60% { transform: rotate(-4deg); }
  68% { transform: rotate(2deg); }
  76% { transform: rotate(-2.5deg); }
  84% { transform: rotate(0deg); }
}
@keyframes hand-wave {
  from { transform: rotate(-13deg); }
  to { transform: rotate(11deg); }
}
@keyframes foot-tap {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(2.6deg); }
}
@keyframes avatar-sway {
  0%, 100% { transform: rotate(-1.2deg); }
  50% { transform: rotate(1.2deg); }
}
@keyframes avatar-think {
  0%, 100% { transform: rotate(-3.2deg) translateY(.4px); }
  50% { transform: rotate(1.2deg) translateY(-.4px); }
}
@keyframes avatar-talk-bob {
  0%, 100% { transform: translateY(0) rotate(0); }
  35% { transform: translateY(-1.4px) rotate(-.8deg); }
  70% { transform: translateY(.6px) rotate(.8deg); }
}
@keyframes avatar-halo {
  0%, 100% { opacity: .55; transform: scale(.96); }
  50% { opacity: 1; transform: scale(1.04); }
}
@keyframes tail-l {
  0%, 100% { transform: rotate(-2.5deg); }
  50% { transform: rotate(4deg); }
}
@keyframes tail-r {
  0%, 100% { transform: rotate(2.5deg); }
  50% { transform: rotate(-4deg); }
}
@keyframes avatar-ahoge {
  0%, 100% { transform: rotate(-5deg); }
  50% { transform: rotate(6deg); }
}
@keyframes blink-l {
  0%, 91%, 100% { transform: scaleY(1); }
  94% { transform: scaleY(.1); }
  97% { transform: scaleY(1); }
}
@keyframes blink-r {
  0%, 61%, 67%, 91%, 100% { transform: scaleY(1); }
  63% { transform: scaleY(.1); }
  65% { transform: scaleY(1); }
  94% { transform: scaleY(.1); }
  97% { transform: scaleY(1); }
}
@keyframes blink-think {
  0%, 93%, 100% { transform: scaleY(1); }
  96% { transform: scaleY(.12); }
}
@keyframes glance {
  0%, 18%, 82%, 100% { transform: translateX(0); }
  28%, 42% { transform: translateX(1.1px); }
  54%, 68% { transform: translateX(-1.1px); }
}
@keyframes mouth-think {
  0%, 100% { transform: scale(.9); }
  50% { transform: scale(1.1); }
}
@keyframes avatar-talk {
  from { transform: scaleY(.5) scaleX(1.05); }
  to { transform: scaleY(1.25) scaleX(.92); }
}
@keyframes hand-bob {
  0%, 100% { transform: translateY(0) rotate(0); }
  50% { transform: translateY(-1px) rotate(-1.5deg); }
}
@keyframes core-pulse {
  0%, 100% { opacity: .35; transform: scale(.85); }
  50% { opacity: .9; transform: scale(1.25); }
}
@keyframes led-blink {
  0%, 100% { opacity: 1; }
  50% { opacity: .25; }
}
@keyframes thought-rise {
  0%, 100% { opacity: .45; transform: translateY(0) scale(.9); }
  50% { opacity: 1; transform: translateY(-2px) scale(1.1); }
}
@keyframes sparkle {
  0%, 100% { opacity: .25; transform: scale(.8) rotate(0deg); }
  50% { opacity: 1; transform: scale(1.15) rotate(20deg); }
}

@media (prefers-reduced-motion: reduce) {
  .bob, .head, .halo, .halo-dots, .tail, .ahoge, .eye, .iris, .mouth-think, .mouth-talk,
  .arm-think, .core-glow, .headset-led, .think-dots circle, .spark, .shadow,
  .arm-l-idle, .arm-r-wave, .hand-wave, .leg-r { animation: none !important; }
}
</style>