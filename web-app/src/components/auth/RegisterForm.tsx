import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { zodResolver } from '@hookform/resolvers/zod'
import { AlertCircle, Eye, EyeOff } from 'lucide-react'
import { isAxiosError } from 'axios'
import { toast } from 'sonner'
import { Input } from '@/components/ui/Input'
import { Button } from '@/components/ui/Button'
import { registerSchema, type RegisterFormData } from '@/schemas/auth.schema'
import { useAuthStore } from '@/stores/auth.store'
import type { ApiResponse } from '@/types/auth.types'

export function RegisterForm() {
  const [showPassword, setShowPassword] = useState(false)
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const { register: registerUser, isLoading } = useAuthStore()

  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterFormData>({
    resolver: zodResolver(registerSchema),
    defaultValues: {
      email: '',
      username: '',
      password: '',
      confirmPassword: '',
    },
  })

  const onSubmit = async (data: RegisterFormData) => {
    setErrorMessage(null)
    try {
      await registerUser(data)
      toast.success('Đăng ký tài khoản thành công! Chào mừng bạn gia nhập RealLive.')
    } catch (error) {
      if (isAxiosError<ApiResponse<unknown>>(error)) {
        const responseData = error.response?.data
        if (responseData?.code === 1005) {
          setErrorMessage('Tên đăng nhập đã tồn tại, vui lòng chọn tên khác')
        } else if (responseData?.code === 1006) {
          setErrorMessage('Email này đã được sử dụng')
        } else if (responseData?.code === 1007) {
          setErrorMessage('Mật khẩu xác nhận không khớp')
        } else if (responseData?.message) {
          setErrorMessage(responseData.message)
        } else if (error.message.includes('Network Error')) {
          setErrorMessage('Không thể kết nối đến máy chủ API Gateway (Port 8888)')
        } else {
          setErrorMessage('Đăng ký thất bại. Vui lòng kiểm tra lại thông tin.')
        }
      } else {
        setErrorMessage('Đã xảy ra lỗi không xác định')
      }
    }
  }

  return (
    <form onSubmit={handleSubmit(onSubmit)} className="space-y-3.5">
      {errorMessage && (
        <div className="flex items-start gap-2.5 p-3 rounded-lg bg-red-500/10 border border-red-500/30 text-red-400 text-xs animate-in fade-in">
          <AlertCircle className="w-4 h-4 flex-shrink-0 mt-0.5" />
          <span>{errorMessage}</span>
        </div>
      )}

      <Input
        id="register-email"
        label="Email"
        type="email"
        placeholder="Enter your email"
        autoComplete="email"
        error={errors.email?.message}
        {...register('email')}
      />

      <Input
        id="register-username"
        label="Username"
        placeholder="Choose a username (min 5 characters)"
        autoComplete="username"
        error={errors.username?.message}
        {...register('username')}
      />

      <Input
        id="register-password"
        label="Password"
        type={showPassword ? 'text' : 'password'}
        placeholder="Create password (min 5 characters)"
        autoComplete="new-password"
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

      <Input
        id="register-confirmPassword"
        label="Confirm Password"
        type={showPassword ? 'text' : 'password'}
        placeholder="Confirm your password"
        autoComplete="new-password"
        error={errors.confirmPassword?.message}
        {...register('confirmPassword')}
      />

      <Button
        type="submit"
        variant="primary"
        size="lg"
        className="w-full mt-3"
        isLoading={isLoading}
      >
        Create Account
      </Button>
    </form>
  )
}
