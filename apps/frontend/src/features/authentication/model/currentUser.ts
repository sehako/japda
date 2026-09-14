export type CurrentUserRole = 'ADMIN' | 'BUYER'

export interface CurrentUser {
  id: number
  email: string
  roles: CurrentUserRole[]
}
