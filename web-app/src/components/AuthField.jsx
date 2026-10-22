import { useId, useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';

/**
 * Ô nhập có nhãn + gợi ý, dùng chung cho các form auth.
 * `type="password"` tự thêm nút hiện/ẩn.
 */
export default function AuthField({
  label,
  icon: Icon,
  type = 'text',
  value,
  onChange,
  placeholder,
  hint,
  error,
  autoComplete,
  required = false,
  inputMode,
  maxLength,
}) {
  const id = useId();
  const [revealed, setRevealed] = useState(false);
  const isPassword = type === 'password';
  const inputType = isPassword && revealed ? 'text' : type;

  return (
    <div className="form-field">
      <label className="form-field__label" htmlFor={id}>
        {label}
      </label>

      <div className={`form-field__control${error ? ' is-error' : ''}`}>
        {Icon && <Icon size={20} className="form-field__icon" />}
        <input
          id={id}
          type={inputType}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder}
          autoComplete={autoComplete}
          required={required}
          inputMode={inputMode}
          maxLength={maxLength}
          aria-invalid={Boolean(error)}
        />
        {isPassword && (
          <button
            type="button"
            className="form-field__toggle"
            onClick={() => setRevealed((v) => !v)}
            aria-label={revealed ? 'Ẩn mật khẩu' : 'Hiện mật khẩu'}
          >
            {revealed ? <EyeOff size={18} /> : <Eye size={18} />}
          </button>
        )}
      </div>

      {error ? (
        <p className="form-field__error">{error}</p>
      ) : (
        hint && <p className="form-field__hint">{hint}</p>
      )}
    </div>
  );
}
