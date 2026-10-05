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

  /**
   * Ferme la session côté serveur (tous les jetons de l'utilisateur sont
   * révoqués), puis localement. Sans réseau ou avec un jeton déjà expiré, la
   * déconnexion locale a lieu quand même.
   */
  async function logout() {
    if (auth.accessToken) {
      try {
        await $fetch('/auth/logout', {
          baseURL: config.public.apiBase as string,
          method: 'POST',
          headers: { Authorization: `Bearer ${auth.accessToken}` },
          timeout: 5000,
        })
      } catch {
        // Déconnexion locale malgré tout.
      }
    }
    auth.logout()
    navigateTo('/login')
  }

  return { login, logout, auth }
}
