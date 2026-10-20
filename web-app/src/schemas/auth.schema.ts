import { z } from 'zod'

export const loginSchema = z.object({
  username: z
    .string()
    .min(1, 'Please enter your username')
    .min(5, 'Username must contain at least 5 characters'),
  password: z
    .string()
    .min(1, 'Please enter your password')
    .min(5, 'Password must contain at least 5 characters'),
})

export type LoginFormData = z.infer<typeof loginSchema>

export const registerSchema = z
  .object({
    email: z
      .string()
      .min(1, 'Please enter your email')
      .email('Please enter a valid email address'),
    username: z
      .string()
      .min(1, 'Please enter your username')
      .min(5, 'Username must contain at least 5 characters')
      .regex(
        /^[a-zA-Z0-9_.-]+$/,
        'Username can only contain letters, numbers, underscores, hyphens or periods',
      ),
    password: z
      .string()
      .min(1, 'Please create a password')
      .min(5, 'Password must contain at least 5 characters'),
    confirmPassword: z
      .string()
      .min(1, 'Please confirm your password')
      .min(5, 'Password confirmation must contain at least 5 characters'),
  })
  .refine((data) => data.password === data.confirmPassword, {
    message: 'Passwords do not match',
    path: ['confirmPassword'],
  })

export type RegisterFormData = z.infer<typeof registerSchema>
