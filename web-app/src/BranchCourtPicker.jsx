import React from 'react';
import {filterCourts} from './lib/courtSelection';

const label = court => `Sân ${String(court.branchCourtNumber ?? court.courtNumber).padStart(2, '0')}`;

// Controls embedded below the existing card's time checkboxes; never renders another card.
export default function BranchCourtPicker({courts, availableSlots, selections, requestedHours, onToggleCourt, disabled}) {
  return (
    <fieldset className="customer-branch-courts"><legend>Sân trống:</legend>
      {filterCourts(courts, requestedHours, availableSlots).map(court => {
        const checked = Object.hasOwn(selections, court.id);
        return <label key={court.id} className={checked ? 'customer-slot-chip selected' : 'customer-slot-chip'}>
          <input type="checkbox" value={court.id} checked={checked} disabled={disabled} onChange={event => onToggleCourt(court.id, event.target.checked)}/>{label(court)}
        </label>;
      })}
    </fieldset>
  );
}
