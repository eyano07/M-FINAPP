// Application Android MBSC Finapp (17/11/2026) : le site, ouvert dans la vue web de l'application, y sert à valider les
// notes de frais et à consulter le tableau de bord, les notes de frais et la comptabilité. Le caissier y tient en plus
// la trésorerie (caisse, banque et mobile money si leur module est activé, avec leurs journaux), la vente, les clients
// et les fournisseurs : il encaisse, décaisse et vend depuis son téléphone.
//
// L'application ajoute « MBSCFinappAndroid » à l'agent utilisateur de toutes ses requêtes. Le serveur s'en sert pour
// refuser toute saisie autre que les décisions sur une note et, pour le caissier, ses saisies de trésorerie et de vente
// (ApplicationMobileFilter) ; ici, on n'affiche que les pages utiles.

export const AGENT_APPLICATION_MOBILE = 'MBSCFinappAndroid'

/** Pont vers l'application (window.FinappAndroid), présent seulement dans l'application. */
export interface PontApplicationMobile {
  jetonPush(): string
  version(): string
  configurer(): void
  /** Widgets de l'écran d'accueil (application ≥ 18/11/2026) : jeton du widget et compte à qui il appartient. */
  jetonWidget?(): string
  utilisateurWidget?(): string
  definirJetonWidget?(jeton: string, utilisateur: string): void
  /** Pose un widget sur l'écran d'accueil (application ≥ 2.9) : "ok", "non-supporte" ou "erreur". */
  ajouterWidget?(type: 'notes' | 'tresorerie'): string
}

/** Application MBSC Logistique (26/11/2026) : agent utilisateur propre, le site s'y ouvre sans restriction. */
export const AGENT_APPLICATION_LOGISTIQUE = 'MBSCLogistiqueAndroid'

export function estApplicationLogistique(): boolean {
  return import.meta.client && typeof navigator !== 'undefined' && navigator.userAgent.includes(AGENT_APPLICATION_LOGISTIQUE)
}

export function estApplicationMobile(): boolean {
  return import.meta.client && typeof navigator !== 'undefined' && navigator.userAgent.includes(AGENT_APPLICATION_MOBILE)
}

export function pontApplicationMobile(): PontApplicationMobile | null {
  if (!import.meta.client) return null
  return ((window as any).FinappAndroid as PontApplicationMobile | undefined) ?? null
}

/** Pages de saisie fermées dans l'application, même sous une rubrique permise. */
const PAGES_DE_SAISIE = [
  '/notes-frais/parametres-priorite',
  '/comptabilite/solde-ouverture',
]

/**
 * Pages du caissier dans l'application, saisie comprise (nouvelle vente…) : caisse, banque, mobile money (opérations,
 * journaux, rapport de caisse), ventes et clients, fournisseurs. Banque et mobile money n'apparaissent que si leur
 * module est activé : le menu et la garde de page vérifient le module comme sur le site.
 */
const PAGES_CAISSIER = ['/caisse', '/banque', '/mobile-money', '/ventes', '/logistique/fournisseurs']

function sous(chemin: string, racine: string) {
  return chemin === racine || chemin.startsWith(racine + '/')
}

/**
 * Page ouverte dans l'application : tableau de bord, notes de frais, comptabilité (consultation), compte ; pour le
 * caissier, en plus, la trésorerie, la vente, les clients et les fournisseurs.
 */
export function routePermiseDansApplication(chemin: string, roles: string[] = []): boolean {
  const c = chemin.replace(/\/$/, '') || '/'
  if (['/login', '/changer-mot-de-passe', '/double-authentification', '/profil', '/aide', '/a-propos', '/'].includes(c)) return true
  if (roles.includes('CAISSIER') && PAGES_CAISSIER.some(p => sous(c, p))) return true
  // Le DA fixe les seuils de priorité des notes depuis son téléphone (le serveur l'accepte du DA seul).
  if (roles.includes('DA') && c === '/notes-frais/parametres-priorite') return true
  if (PAGES_DE_SAISIE.some(p => sous(c, p))) return false
  if (/\/(nouvelle|nouveau|modifier|creer)(\/|$)/.test(c)) return false
  return sous(c, '/dashboard') || sous(c, '/notes-frais') || sous(c, '/comptabilite')
}

/** Accueil dans l'application : une page que le rôle peut ouvrir (même règle que les gardes de ces pages). */
export function accueilApplication(roles: string[]): string {
  if (roles.some(r => ['ADMIN', 'DG', 'DA', 'DFIN', 'CAISSIER', 'COMPTABLE'].includes(r))) return '/dashboard'
  if (roles.some(r => ['DIRECTEUR', 'GEST_PATRIMOINE', 'RESP_RESTAURANT', 'RESP_CUISINE', 'RESP_BAR', 'LOGISTIQUE'].includes(r))) return '/notes-frais'
  return '/profil'
}
