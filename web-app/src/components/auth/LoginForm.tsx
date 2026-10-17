import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { AlertCircle, Eye, EyeOff } from 'lucide-react'
import { isAxiosError } from 'axios'
import { toast } from 'sonner'
import { Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'
import { loginSchema, type LoginFormData } from '@/schemas/auth.schema'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'

export function LoginForm() {
  const [showPassword, setShowPassword] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const { login, isLoading } = useAuthStore()

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormData>({
    resolver: zodResolver(loginSchema),
    defaultValues: {
      username: '',
      password: '',
    },
  })

  const onSubmit = async (data: LoginFormData) => {
    setErrorMessage(null)
    try {
      await login(data)
      toast.success('Đăng nhập thành công! Chào mừng bạn trở lại.')
    } catch (error) {
      if (isAxiosError<ApiResponse<unknown>>(error)) {
        const responseData = error.response?.data
        if (responseData?.code === 1001) {
          setErrorMessage('Tên đăng nhập hoặc mật khẩu không chính xác')
        } else if (responseData?.message) {
          setErrorMessage(responseData.message)
        } else if (error.message.includes('Network Error')) {
          setErrorMessage('Không thể kết nối đến máy chủ API Gateway (Port 8888)')
        } else {
          setErrorMessage('Đăng nhập thất bại. Vui lòng thử lại sau.')
        }
      } else {
        setErrorMessage('Đã xảy ra lỗi không xác định')
      }
    }
  }

  const handleForgotPassword = (e: React.MouseEvent) => {
    e.preventDefault()
    toast.info('Tính năng quên mật khẩu đang được xử lý.')
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-4">
      {errorMessage && (
        <div className="flex items-start gap-2.5 p-3 rounded-lg bg-red-500/10 border border-red-500/30 text-red-400 text-xs animate-in fade-in">
          <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
          <span>{errorMessage}</span>
        </div>
      )}

      <Input
        id="login-username"
        label="Username"
        placeholder="Enter your username"
        autoComplete="username"
        error={errors.username?.message}
        {...register('username')}
      />

      <Input
        id="login-password"
        label="Password"
        type={showPassword ? 'text' : 'password'}
        placeholder="Enter your password"
        autoComplete="current-password"
        error={errors.password?.message}
        rightElement={
          <button
            type="button"
            onClick={() => setShowPassword(!showPassword)}
            className="text-gray-400 hover:text-gray-200 focus:outline-none transition"
            aria-label={showPassword ? 'Hide password' : 'Show password'}
          >
            {showPassword ? (
              <EyeOff className="w-4 h-4" />
            ) : (
              <Eye className="w-4 h-4" />
            )}
          </button>
        }
        {...register('password')}
      />

      <div className="flex justify-end">
        <button
          type="button"
          onClick={handleForgotPassword}
          className="text-xs font-medium text-gray-400 hover:text-[#ff2b42] transition cursor-pointer"
        >
          Forgot password?
        </button>
      </div>

      <Button
        type="submit"
        variant="primary"
        size="lg"
        className="w-full mt-2"
        isLoading={isLoading}
      >
        Log In
      </Button>
    </form>
  )
}
