import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'cyan' | 'secondary' | 'ghost' | 'danger' | 'success'
  size?: 'sm' | 'md' | 'lg'
  isLoading?: boolean
  leftIcon?: ReactNode
  rightIcon?: ReactNode
}

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  (
    {
      className,
      children,
      variant = 'primary',
      size = 'md',
      isLoading = false,
      disabled,
      leftIcon,
      rightIcon,
      ...props
    },
    ref,
  ) => {
    const variantStyles = {
      primary:
        'bg-[#ff2b42] hover:bg-[#e02237] text-white font-extrabold shadow-lg hover:shadow-red-500/25',
      cyan: 'bg-[#ff2b42] hover:bg-[#e02237] text-white font-bold neon-glow-red',
      secondary:
        'bg-[#101014] hover:bg-[#18181f] border border-[#1f1f26] text-gray-200 font-medium',
      ghost:
        'text-gray-400 hover:text-white hover:bg-[#18181f] font-semibold',
      danger:
        'bg-red-600 hover:bg-red-700 text-white font-bold shadow-lg hover:shadow-red-600/25',
      success:
        'bg-emerald-600 hover:bg-emerald-500 text-white font-bold shadow-lg hover:shadow-emerald-600/25',
    }

    const sizeStyles = {
      sm: 'py-1.5 px-3 text-xs rounded-lg',
      md: 'py-2.5 px-4 text-sm rounded-lg',
      lg: 'py-3 px-4 text-sm font-extrabold rounded-lg',
    }

    return (
      <button
        ref={ref}
        disabled={disabled || isLoading}
        className={cn(
          'inline-flex items-center justify-center gap-2 transition select-none cursor-pointer',
          'disabled:opacity-60 disabled:cursor-not-allowed disabled:pointer-events-none',
          variantStyles[variant],
          sizeStyles[size],
          className,
        )}
        {...props}
      >
        {isLoading ? (
          <Loader2 className="w-4 h-4 animate-spin text-current" />
        ) : (
          leftIcon
        )}
        {children}
        {!isLoading && rightIcon}
      </button>
    )
  },
)

Button.displayName = 'Button'
