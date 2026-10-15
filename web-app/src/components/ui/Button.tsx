import { forwardRef, type ButtonHTMLAttributes, type ReactNode } from 'react'
import { Loader2 } from 'lucide-react'
import { cn } from '@/lib/utils'

export interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'cyan' | 'secondary' | 'ghost'
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
        'bg-[#53fc18] hover:bg-[#46e012] text-black font-extrabold shadow-lg hover:shadow-emerald-500/20',
      cyan: 'bg-[#00f0ff] hover:bg-[#00c8d6] text-black font-bold neon-glow-cyan',
      secondary:
        'bg-[#151822] hover:bg-[#1f2433] border border-[#232838] text-gray-200 font-medium',
      ghost:
        'text-gray-400 hover:text-white hover:bg-[#1f2433] font-semibold',
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
