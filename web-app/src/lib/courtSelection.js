// Explicit court/hour choices only; availability always comes from the backend grid.
export function unionSlots(courts, available) {
  const slots = new Map();
  for (const court of courts) for (const slot of available.get(court.id) || []) {
    if (!slots.has(slot.timeSlotId)) slots.set(slot.timeSlotId, slot);
  }
  return [...slots.values()].sort((a, b) => a.startTime.localeCompare(b.startTime));
}

export function matchingHours(courtId, hours, available) {
  const free = new Set((available.get(courtId) || []).map(slot => slot.timeSlotId));
  return [...new Set(hours)].filter(id => free.has(id));
}

export function filterCourts(courts, hours, available) {
  return courts.filter(court => (available.get(court.id) || []).length > 0
    && matchingHours(court.id, hours, available).length === new Set(hours).size);
}

export function filterHours(courts, selections, available) {
  const chosen = courts.filter(court => Object.hasOwn(selections, court.id));
  return unionSlots(chosen.length ? chosen : courts, available).filter(slot =>
    chosen.every(court => matchingHours(court.id, [slot.timeSlotId], available).length > 0));
}

export function reconcileVenue(courts, selections, hours, available) {
  const freeHours = new Set(unionSlots(courts, available).map(slot => slot.timeSlotId));
  const validHours = [...new Set(hours)].filter(id => freeHours.has(id));
  const eligible = new Set(filterCourts(courts, validHours, available).map(court => court.id));
  const ids = new Set(courts.map(court => court.id));
  const next = Object.fromEntries(Object.entries(selections).filter(([id]) => !ids.has(id)));
  for (const court of courts) if (Object.hasOwn(selections, court.id) && eligible.has(court.id)) {
    next[court.id] = matchingHours(court.id, validHours, available);
  }
  return {selections: next, hours: validHours};
}

export function toggleCourt(selections, courtId, checked, hours, available) {
  if (checked && !filterCourts([{id: courtId}], hours, available).length) return selections;
  const next = {...selections};
  if (checked) next[courtId] = matchingHours(courtId, hours, available);
  else delete next[courtId];
  return next;
}

export function toggleCourtHour(selections, courtId, hourId, checked, available) {
  if (!Object.hasOwn(selections, courtId)) return selections;
  if (checked && !matchingHours(courtId, [hourId], available).length) return selections;
  return {...selections, [courtId]: checked
    ? [...new Set([...selections[courtId], hourId])]
    : selections[courtId].filter(id => id !== hourId)};
}

export function toggleFilterHour(selections, courts, hourId, checked, available) {
  return courts.reduce((next, court) => toggleCourtHour(next, court.id, hourId, checked, available), selections);
}

export function prunePairs(selections, available) {
  const removed = [];
  const next = Object.fromEntries(Object.entries(selections).map(([courtId, hours]) => {
    const valid = matchingHours(courtId, hours, available);
    for (const hourId of hours) if (!valid.includes(hourId)) removed.push({courtId, hourId});
    return [courtId, valid];
  }));
  return {selections: next, removed};
}

export function selectedPairs(courts, selections, available, bookingDate) {
  return courts.flatMap(court => matchingHours(court.id, selections[court.id] || [], available)
    .map(timeSlotId => ({courtId: court.id, timeSlotId, bookingDate})));
}
