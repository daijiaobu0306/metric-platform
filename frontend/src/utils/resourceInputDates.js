// Keep source names unchanged for collection matching and query parameters.
export function formatResourceSubcategory(name) {
  return ({ '需求开发-bass': '需求开发-BASS', '需求开发-boss': '需求开发-BOSS' })[name] || name
}

// Match LocalDate.now(Asia/Shanghai).minusMonths(1), including month-end clamping.
export function resourceInputDates() {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const get = type => Number(parts.find(p => p.type === type).value)
  const year = get('year'), month = get('month'), day = get('day')
  const prior = new Date(Date.UTC(year, month - 2, 1))
  const last = new Date(Date.UTC(year, month - 1, 0)).getUTCDate()
  const format = (y, m, d) => `${y}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`
  return [format(prior.getUTCFullYear(), prior.getUTCMonth() + 1, Math.min(day, last)), format(year, month, day)]
}
