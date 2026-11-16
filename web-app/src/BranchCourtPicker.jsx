import React from 'react';
import {filterCourts} from './lib/courtSelection';

const label = court => `Sân ${String(court.branchCourtNumber ?? court.courtNumber).padStart(2, '0')}`;

export default function BranchCourtPicker({courts, availableSlots, selections, requestedHours, onToggleCourt, disabled}) {
  const freeCourts = new Set(filterCourts(courts, requestedHours, availableSlots).map(c => c.id));
  return (
    <fieldset className="customer-branch-courts"><legend>Sân:</legend>
      {courts.map(court => {
        const isMaintenance = court.status === 'MAINTENANCE';
        const isFree = freeCourts.has(court.id);
        const checked = !isMaintenance && Object.hasOwn(selections, court.id);
        const isItemDisabled = disabled || isMaintenance || !isFree;
        return (
          <label
            key={court.id}
            title={isMaintenance ? 'Sân đang bảo trì' : !isFree ? 'Không còn giờ trống' : undefined}
            className={`customer-slot-chip${checked ? ' selected' : ''}${isMaintenance ? ' maintenance' : ''}`}
          >
            <input
              type="checkbox"
              value={court.id}
              checked={checked}
              disabled={isItemDisabled}
              onChange={event => onToggleCourt(court.id, event.target.checked)}
            />
            {label(court)}{isMaintenance ? ' (Bảo trì)' : ''}
          </label>
        );
      })}
    </fieldset>
  );
}
