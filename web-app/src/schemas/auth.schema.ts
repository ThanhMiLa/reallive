import { z } from 'zod'

export const loginSchema = z.object({
  username: z
    .string()
    .min(1, 'Vui lòng nhập tên đăng nhập')
    .min(5, 'Tên đăng nhập phải có ít nhất 5 ký tự'),
  password: z
    .string()
    .min(1, 'Vui lòng nhập mật khẩu')
    .min(5, 'Mật khẩu phải có ít nhất 5 ký tự'),
})

export type LoginFormData = z.infer<typeof loginSchema>

export const registerSchema = z
  .object({
    email: z
      .string()
      .min(1, 'Vui lòng nhập email')
      .email('Email không đúng định dạng'),
    username: z
      .string()
      .min(1, 'Vui lòng nhập tên đăng nhập')
      .min(5, 'Tên đăng nhập phải có ít nhất 5 ký tự')
      .regex(
        /^[a-zA-Z0-9_.-]+$/,
        'Tên đăng nhập chỉ chứa chữ cái, số, dấu gạch dưới, gạch ngang hoặc chấm',
      ),
    password: z
      .string()
      .min(1, 'Vui lòng tạo mật khẩu')
      .min(5, 'Mật khẩu phải có ít nhất 5 ký tự'),
    confirmPassword: z
      .string()
      .min(1, 'Vui lòng xác nhận mật khẩu')
      .min(5, 'Mật khẩu xác nhận phải có ít nhất 5 ký tự'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Mật khẩu xác nhận không khớp',
    path: ['confirmPassword'],
  })

export type RegisterFormData = z.infer<typeof registerSchema>
