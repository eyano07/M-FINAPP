import type { MaybeRefOrGetter } from 'vue'

/**
 * Titre de la page courante, affiché dans la barre d'application sur téléphone et tablette (< 960 px).
 *
 * La barre le déduit seule, dans cet ordre : titre posé ici > `definePageMeta({ titre })` > titre `h1` de la page (celui que
 * l'utilisateur voit sur PC) > rien (la barre garde alors le nom de l'entreprise). Une page n'appelle donc ce composable que
 * pour un titre qu'aucun `h1` ne porte (note de frais « NF-2026-000011 », fiche chargée par son identifiant…).
 *
 *   usePageTitle(() => note.value ? `Note ${note.value.reference}` : 'Note de frais')
 *
 * Ne touche ni `useHead` ni `document.title` (nom du fichier PDF du rapport de caisse).
 */
interface TitrePage { chemin: string; titre: string }

export function usePageTitle(titre?: MaybeRefOrGetter<string | null | undefined>) {
  const etat = useState<TitrePage>('page-titre', () => ({ chemin: '', titre: '' }))
  if (titre !== undefined) {
    const chemin = useRoute().path
    watchEffect(() => { etat.value = { chemin, titre: toValue(titre) ?? '' } })
    // Une page qui se ferme ne retire que son propre titre : la suivante a pu poser le sien avant.
    onBeforeUnmount(() => { if (etat.value.chemin === chemin) etat.value = { chemin: '', titre: '' } })
  }
  return etat
}
