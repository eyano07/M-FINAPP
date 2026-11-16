"""Retire la chaîne de calcul (xl/calcChain.xml) d'un classeur xlsx, en place.

Après suppression de feuilles par POI, la chaîne peut citer des cellules qui
n'existent plus : Excel signale alors un classeur « à réparer ». Sans chaîne,
Excel la reconstruit simplement à l'ouverture.

Usage : python3 -I sans_calcchain.py modele.xlsx
"""
import re
import shutil
import sys
import tempfile
import zipfile

chemin = sys.argv[1]
with tempfile.NamedTemporaryFile(delete=False, suffix=".xlsx") as tmp:
    sortie = tmp.name
with zipfile.ZipFile(chemin) as zin, zipfile.ZipFile(sortie, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        if item.filename == "xl/calcChain.xml":
            continue
        data = zin.read(item.filename)
        if item.filename == "[Content_Types].xml":
            data = re.sub(rb'<Override[^>]*PartName="/xl/calcChain.xml"[^>]*/>', b"", data)
        elif item.filename == "xl/_rels/workbook.xml.rels":
            data = re.sub(rb'<Relationship[^>]*Target="[^"]*calcChain.xml"[^>]*/>', b"", data)
        zout.writestr(item, data)
shutil.move(sortie, chemin)
