const isSharedScreen = /^(?:\/metric-code)?\/screen\/s\/[A-Za-z0-9_-]+\/?$/.test(window.location.pathname)

const entries = {
  share: () => import('./share-entry'),
  platform: () => import('./platform-entry')
}

entries[isSharedScreen ? 'share' : 'platform']()
