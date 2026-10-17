import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react'
import { cn } from '@/lib/utils'

export interface InputProps extends InputHTMLAttributes<HTMLInputElement> {
  label?: string
  error?: string
  rightElement?: ReactNode
}

export const Input = forwardRef<HTMLInputElement, InputProps>(
  ({ className, label, error, rightElement, id, ...props }, ref) => {
    return (
      <div className="space-y-1.5 w-full">
        {label && (
          <label
            htmlFor={id}
            className="block text-xs font-semibold text-gray-300"
          >
            {label}
          </label>
        )}
        <div className="relative flex items-center">
          <input
            id={id}
            ref={ref}
            className={cn(
              'w-full bg-[#101014] border border-[#1f1f26] rounded-lg px-3.5 py-2.5 text-sm text-white placeholder-gray-500 transition',
              'focus:outline-none focus:border-[#ff2b42] focus:ring-1 focus:ring-[#ff2b42]',
              error && 'border-red-500/80 focus:border-red-500 focus:ring-red-500',
              rightElement && 'pr-10',
              className,
            )}
            {...props}
          />
          {rightElement && (
            <div className="absolute right-3 flex items-center">
              {rightElement}
            </div>
          )}
        </div>
        {error && <p className="text-[11px] font-medium text-red-400">{error}</p>}
      </div>
    )
  },
)

Input.displayName = 'Input'
