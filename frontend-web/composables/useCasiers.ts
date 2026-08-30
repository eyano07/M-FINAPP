/**
 * Conversion entre un stock de bouteilles vides et son affichage métier
 * « N casiers + M bouteilles ».
 *
 * Le nombre de casiers n'est jamais stocké : il se déduit du seul compteur de
 * bouteilles vides. Cette conversion est donc utilisée par tous les écrans du
 * module Restaurant — elle vit ici pour rester cohérente partout plutôt que
 * d'être recopiée dans chacun.
 */
export function casiersEtBouteilles(vides: number, contenance: number) {
  if (!contenance || contenance <= 0) {
    return { casiers: 0, bouteilles: vides || 0 }
  }
  const total = vides || 0
  return { casiers: Math.floor(total / contenance), bouteilles: total % contenance }
}

/** « 4 casiers + 15 bouteilles », ou « 15 bouteilles » s'il n'y a pas de casier complet. */
export function formatCasiers(vides: number, contenance: number): string {
  const { casiers, bouteilles } = casiersEtBouteilles(vides, contenance)
  const parts: string[] = []
  if (casiers > 0) parts.push(`${casiers} casier${casiers > 1 ? 's' : ''}`)
  if (bouteilles > 0 || casiers === 0) parts.push(`${bouteilles} bouteille${bouteilles > 1 ? 's' : ''}`)
  return parts.join(' + ')
}
