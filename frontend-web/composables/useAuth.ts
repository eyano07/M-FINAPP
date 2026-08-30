import { useAuthStore, type AuthUser } from '~/stores/auth'

interface AuthResponse {
  accessToken: string
  refreshToken: string
  tokenType: string
  expiresIn: number
  user: AuthUser
}

/**
 * Operations d'authentification (login / logout) cote client.
 */
export function useAuth() {
  const auth = useAuthStore()
  const config = useRuntimeConfig()

  async function login(email: string, motDePasse: string) {
    const res = await $fetch<AuthResponse>('/auth/login', {
      baseURL: config.public.apiBase as string,
      method: 'POST',
      body: { email, motDePasse },
    })
    auth.setSession({
      accessToken: res.accessToken,
      refreshToken: res.refreshToken,
      user: res.user,
    })
    return res
  }

  function logout() {
    auth.logout()
    navigateTo('/login')
  }

  return { login, logout, auth }
}
