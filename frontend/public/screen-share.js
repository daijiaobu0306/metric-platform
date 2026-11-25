const id = new URLSearchParams(location.search).get('s')
if (id && /^[A-Za-z0-9_-]{20,48}$/.test(id)) {
  location.replace(new URL(`screen/s/${id}${location.hash}`, location.href))
} else {
  document.getElementById('app').textContent = '链接无效，请联系专屏管理员。'
}
