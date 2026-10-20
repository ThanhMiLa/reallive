import { z } from 'zod'

export const categoryPreferencesSchema = z.object({
  categoryIds: z.array(z.string().uuid())
    .min(1, 'Choose at least one category.')
    .max(20, 'You can choose up to 20 categories.'),
})
