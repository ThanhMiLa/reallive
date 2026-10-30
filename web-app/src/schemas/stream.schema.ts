import { z } from 'zod'

export const streamDetailsSchema = z.object({
  title: z
    .string()
    .trim()
    .min(1, 'Enter a stream title.')
    .max(255, 'Title must be at most 255 characters.'),
  categoryId: z.uuid('Choose a category.'),
})
