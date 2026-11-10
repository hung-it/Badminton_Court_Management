const names = new Intl.Collator('vi', {numeric: true, sensitivity: 'base'});
const validPrice = value => value != null && String(value).trim() !== '' && Number.isFinite(Number(value)) && Number(value) >= 0;

const courtBadges = ['Sân cầu lông', 'Lựa chọn sân', 'Chọn lịch chơi', 'Khám phá sân', 'Hẹn giờ ra sân'];
export function courtBadge(id) {
  const hash = [...String(id)].reduce((value, char) => (Math.imul(value, 31) + char.charCodeAt(0)) >>> 0, 0);
  return courtBadges[hash % courtBadges.length];
}

// Reference the first selected available slot, then the time filter, then the lowest
// available slot price. All amounts come from the backend availability response.
export function courtReferencePrice(slots, selectedIds = [], filterSlot = '') {
  const priced = slots.filter(slot => validPrice(slot.price));
  for (const id of selectedIds) {
    const selected = priced.find(slot => slot.timeSlotId === id);
    if (selected) return Number(selected.price);
  }
  if (filterSlot) {
    const filtered = priced.find(slot => slot.timeSlotId === filterSlot);
    return filtered ? Number(filtered.price) : null;
  }
  return priced.length ? Math.min(...priced.map(slot => Number(slot.price))) : null;
}

export function compareCourts(left, right, order, prices) {
  const stable = () => names.compare(left.name || '', right.name || '') || String(left.id).localeCompare(String(right.id));
  if (order === 'price' || order === 'price-desc') {
    const a = prices[left.id], b = prices[right.id];
    const hasA = validPrice(a), hasB = validPrice(b);
    if (hasA !== hasB) return hasA ? -1 : 1;
    if (hasA && Number(a) !== Number(b)) return order === 'price' ? Number(a) - Number(b) : Number(b) - Number(a);
  }
  return stable();
}
