#!/usr/bin/env python3
"""
Coffre-fort JARVIS — stockage partagé, chiffré et versionné des données de la société.

Plusieurs installations de JARVIS (téléphone, tablette, salariés) y synchronisent les données de la société.
Les données arrivent DÉJÀ CHIFFRÉES par les téléphones (AES-256-GCM, clé de la société) : ce serveur ne voit jamais
leur contenu. Il gère : les appareils autorisés et leurs droits (admin / technicien), les invitations à usage unique,
la récupération après perte d'un téléphone, l'historique des versions (20 par objet), la corbeille (30 jours) et une
sauvegarde chaque nuit.

Bibliothèque standard Python uniquement (aucune dépendance) ; HTTPS avec un certificat auto-signé dont l'empreinte est
épinglée par JARVIS (aucun nom de domaine nécessaire).
"""
import hashlib
import hmac
import json
import os
import re
import secrets
import shutil
import sqlite3
import ssl
import subprocess
import sys
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlparse

VERSION = "1.0"
DATA = os.environ.get("COFFRE_DATA", "/data")
PORT = int(os.environ.get("COFFRE_PORT", "8443"))
SAUVEGARDES = os.environ.get("COFFRE_SAUVEGARDES", os.path.join(DATA, "sauvegardes"))
GARDER_VERSIONS = int(os.environ.get("COFFRE_VERSIONS", "20"))
CORBEILLE_JOURS = int(os.environ.get("COFFRE_CORBEILLE_JOURS", "30"))
GARDER_SAUVEGARDES = int(os.environ.get("COFFRE_SAUVEGARDES_GARDEES", "14"))
MAX_OBJET = 200 * 1024 * 1024

CATEGORIES = ["base", "clients", "documents", "paie", "interventions", "fichiers"]
ROLES = {
    "admin": CATEGORIES,
    "technicien": ["base", "clients", "interventions"],
}
CHEMIN_OK = re.compile(r"^(donnees|fichiers)/[A-Za-z0-9._ \-/()À-ÿ]{1,400}$")

_verrou = threading.RLock()


def maintenant():
    return int(time.time())


def journal(*args):
    print(time.strftime("%Y-%m-%d %H:%M:%S"), *args, flush=True)


# ---------------------------------------------------------------------------------------------------------- base


def db():
    c = sqlite3.connect(os.path.join(DATA, "coffre.db"), timeout=30, isolation_level=None, check_same_thread=False)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA journal_mode=WAL")
    c.execute("PRAGMA foreign_keys=ON")
    return c


def initialiser_base():
    with db() as c:
        c.executescript("""
        CREATE TABLE IF NOT EXISTS meta(cle TEXT PRIMARY KEY, valeur TEXT);
        CREATE TABLE IF NOT EXISTS appareils(id TEXT PRIMARY KEY, nom TEXT, role TEXT, categories TEXT, jeton_hash TEXT UNIQUE,
            cree INTEGER, vu INTEGER, revoque INTEGER DEFAULT 0);
        CREATE TABLE IF NOT EXISTS invitations(code_hash TEXT PRIMARY KEY, role TEXT, nom TEXT, expire INTEGER, utilise INTEGER DEFAULT 0,
            cree_par TEXT);
        CREATE TABLE IF NOT EXISTS objets(chemin TEXT PRIMARY KEY, categorie TEXT, version INTEGER, sha256 TEXT, taille INTEGER,
            modifie INTEGER, appareil TEXT, supprime INTEGER DEFAULT 0, seq INTEGER);
        CREATE INDEX IF NOT EXISTS objets_seq ON objets(seq);
        CREATE TABLE IF NOT EXISTS versions(chemin TEXT, version INTEGER, sha256 TEXT, taille INTEGER, modifie INTEGER, appareil TEXT,
            supprime INTEGER DEFAULT 0, PRIMARY KEY(chemin, version));
        CREATE TABLE IF NOT EXISTS echecs(ip TEXT, t INTEGER);
        """)


def meta(c, cle, defaut=None):
    r = c.execute("SELECT valeur FROM meta WHERE cle=?", (cle,)).fetchone()
    return r["valeur"] if r else defaut


def ecrire_meta(c, cle, valeur):
    c.execute("INSERT INTO meta(cle, valeur) VALUES(?, ?) ON CONFLICT(cle) DO UPDATE SET valeur=excluded.valeur", (cle, str(valeur)))


def prochain_seq(c):
    s = int(meta(c, "seq", "0")) + 1
    ecrire_meta(c, "seq", s)
    return s


def h(texte):
    return hashlib.sha256(texte.encode()).hexdigest()


# ---------------------------------------------------------------------------------------------------------- fichiers


def chemin_blob(sha):
    return os.path.join(DATA, "blobs", sha[:2], sha)


def blob_utilise(c, sha):
    return c.execute("SELECT 1 FROM versions WHERE sha256=? LIMIT 1", (sha,)).fetchone() is not None


def supprimer_blob_si_orphelin(c, sha):
    if sha and not blob_utilise(c, sha):
        try:
            os.remove(chemin_blob(sha))
        except FileNotFoundError:
            pass


# ---------------------------------------------------------------------------------------------------------- TLS


def preparer_tls():
    d = os.path.join(DATA, "tls")
    os.makedirs(d, exist_ok=True)
    cert, cle = os.path.join(d, "cert.pem"), os.path.join(d, "cle.pem")
    if not (os.path.exists(cert) and os.path.exists(cle)):
        journal("Création du certificat HTTPS auto-signé…")
        subprocess.run(["openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt", "ec_paramgen_curve:prime256v1", "-nodes",
                        "-keyout", cle, "-out", cert, "-days", "3650", "-subj", "/CN=coffre-jarvis"],
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        os.chmod(cle, 0o600)
    der = ssl.PEM_cert_to_DER_cert(open(cert).read())
    empreinte = hashlib.sha256(der).hexdigest()
    with open(os.path.join(d, "empreinte.txt"), "w") as f:
        f.write(empreinte + "\n")
    return cert, cle, empreinte


def jeton_installation():
    """Jeton à usage unique pour enregistrer le premier appareil (lu par JARVIS via SSH)."""
    p = os.path.join(DATA, "installation.txt")
    with db() as c:
        if c.execute("SELECT COUNT(*) n FROM appareils").fetchone()["n"] > 0:
            if os.path.exists(p):
                os.remove(p)
            return None
    if not os.path.exists(p):
        with open(p, "w") as f:
            f.write(secrets.token_urlsafe(24) + "\n")
        os.chmod(p, 0o600)
    return open(p).read().strip()


# ---------------------------------------------------------------------------------------------------------- HTTP


class Erreur(Exception):
    def __init__(self, code, message, extra=None):
        super().__init__(message)
        self.code, self.message, self.extra = code, message, extra or {}


class Gestionnaire(BaseHTTPRequestHandler):
    server_version = "CoffreJarvis/" + VERSION
    protocol_version = "HTTP/1.1"
    timeout = 120

    def setup(self):
        self.request.settimeout(30)
        if isinstance(self.request, ssl.SSLSocket):
            self.request.do_handshake()
        super().setup()

    def log_message(self, fmt, *args):  # journal réduit
        pass

    # -- réponses
    def repondre(self, code, corps=None, octets=None, type_contenu="application/json"):
        donnees = octets if octets is not None else json.dumps(corps if corps is not None else {}, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", type_contenu)
        self.send_header("Content-Length", str(len(donnees)))
        self.send_header("Cache-Control", "no-store")
        if self.close_connection:
            self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(donnees)

    def vider_corps(self):
        """Requête refusée avant d'avoir lu son contenu : on le lit (ou on ferme), sinon la requête suivante sur la même
        connexion serait mélangée avec ces octets."""
        if getattr(self, "corps_lu", True):
            return
        self.corps_lu = True
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return
        if n > 4 * 1024 * 1024:
            self.close_connection = True
            return
        while n > 0:
            m = self.rfile.read(min(n, 1 << 20))
            if not m:
                break
            n -= len(m)

    def corps_json(self):
        self.corps_lu = True
        n = int(self.headers.get("Content-Length") or 0)
        if n > 1_000_000:
            raise Erreur(413, "requête trop grosse")
        try:
            return json.loads(self.rfile.read(n) or b"{}")
        except ValueError:
            raise Erreur(400, "JSON invalide")

    def ip(self):
        return self.client_address[0]

    def trop_d_echecs(self, c):
        limite = maintenant() - 600
        c.execute("DELETE FROM echecs WHERE t < ?", (maintenant() - 86400,))
        return c.execute("SELECT COUNT(*) n FROM echecs WHERE ip=? AND t>?", (self.ip(), limite)).fetchone()["n"] >= 10

    def echec(self, c):
        c.execute("INSERT INTO echecs(ip, t) VALUES(?, ?)", (self.ip(), maintenant()))

    def appareil(self, c, admin=False):
        if self.trop_d_echecs(c):
            raise Erreur(429, "trop d'essais : réessaie dans 10 minutes")
        auth = self.headers.get("Authorization", "")
        jeton = auth[7:].strip() if auth.startswith("Bearer ") else ""
        a = c.execute("SELECT * FROM appareils WHERE jeton_hash=? AND revoque=0", (h(jeton),)).fetchone() if jeton else None
        if not a:
            self.echec(c)
            raise Erreur(401, "appareil inconnu ou révoqué")
        c.execute("UPDATE appareils SET vu=? WHERE id=?", (maintenant(), a["id"]))
        if admin and a["role"] != "admin":
            raise Erreur(403, "réservé à l'administrateur")
        return a

    def traiter(self, methode):
        try:
            u = urlparse(self.path)
            chemin = unquote(u.path)
            q = {k: v[0] for k, v in parse_qs(u.query).items()}
            self.corps_lu = int(self.headers.get("Content-Length") or 0) <= 0
            c = db()
            try:
                self.router(c, methode, chemin, q)
            finally:
                c.close()
        except Erreur as e:
            self.vider_corps()
            self.repondre(e.code, dict({"erreur": e.message}, **e.extra))
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:  # noqa
            journal("Erreur interne :", repr(e))
            self.close_connection = True
            self.repondre(500, {"erreur": "erreur interne du coffre"})

    def do_GET(self):
        self.traiter("GET")

    def do_POST(self):
        self.traiter("POST")

    def do_PUT(self):
        self.traiter("PUT")

    def do_DELETE(self):
        self.traiter("DELETE")

    # -- routes
    def router(self, c, m, p, q):
        if m == "GET" and p == "/sante":
            return self.repondre(200, {"ok": True, "version": VERSION, "initialise": c.execute("SELECT COUNT(*) n FROM appareils").fetchone()["n"] > 0})
        if m == "POST" and p in ("/init", "/appairer", "/recuperer", "/invitations"):
            with _verrou:
                return {"/init": self.init, "/appairer": self.appairer, "/recuperer": self.recuperer, "/invitations": self.inviter}[p](c)
        if m == "GET" and p == "/appareils":
            a = self.appareil(c, admin=True)
            l = [dict(id=r["id"], nom=r["nom"], role=r["role"], cree=r["cree"], vu=r["vu"], revoque=bool(r["revoque"]), moi=r["id"] == a["id"])
                 for r in c.execute("SELECT * FROM appareils ORDER BY cree")]
            return self.repondre(200, {"appareils": l})
        if m == "DELETE" and p.startswith("/appareils/"):
            a = self.appareil(c, admin=True)
            cible = p.split("/", 2)[2]
            if cible == a["id"]:
                raise Erreur(400, "impossible de révoquer l'appareil qui fait la demande")
            c.execute("UPDATE appareils SET revoque=1 WHERE id=?", (cible,))
            journal("Appareil révoqué :", cible)
            return self.repondre(200, {"ok": True})
        if m == "GET" and p == "/etat":
            a = self.appareil(c)
            n = c.execute("SELECT COUNT(*) n, COALESCE(SUM(taille),0) t FROM objets WHERE supprime=0").fetchone()
            return self.repondre(200, {"objets": n["n"], "taille": n["t"], "seq": int(meta(c, "seq", "0")), "role": a["role"],
                                       "categories": json.loads(a["categories"]), "derniere_sauvegarde": meta(c, "derniere_sauvegarde", "")})
        if m == "GET" and p == "/objets":
            return self.changements(c, q)
        if p.startswith("/objets/"):
            ch = p[len("/objets/"):]
            if not CHEMIN_OK.match(ch) or ".." in ch.split("/"):
                raise Erreur(400, "chemin invalide")
            if m == "GET":
                return self.lire(c, ch, q)
            if m == "PUT":
                return self.ecrire(c, ch, q)
            if m == "DELETE":
                return self.supprimer(c, ch, q)
        if m == "GET" and p.startswith("/historique/"):
            a = self.appareil(c)
            ch = p[len("/historique/"):]
            o = c.execute("SELECT categorie FROM objets WHERE chemin=?", (ch,)).fetchone()
            if not o or o["categorie"] not in json.loads(a["categories"]):
                raise Erreur(404, "inconnu")
            l = [dict(r) for r in c.execute("SELECT version, sha256, taille, modifie, appareil, supprime FROM versions WHERE chemin=? ORDER BY version DESC", (ch,))]
            return self.repondre(200, {"versions": l})
        raise Erreur(404, "route inconnue")

    # -- appareils
    def nouvel_appareil(self, c, nom, role, categories=None):
        jeton = secrets.token_urlsafe(32)
        ident = secrets.token_hex(8)
        cats = categories or ROLES.get(role, ROLES["technicien"])
        c.execute("INSERT INTO appareils(id, nom, role, categories, jeton_hash, cree, vu) VALUES(?,?,?,?,?,?,?)",
                  (ident, (nom or "Appareil")[:60], role, json.dumps(cats), h(jeton), maintenant(), maintenant()))
        journal("Nouvel appareil :", ident, nom, role)
        return {"jeton": jeton, "appareil": ident, "role": role, "categories": cats}

    def init(self, c):
        if self.trop_d_echecs(c):
            raise Erreur(429, "trop d'essais")
        d = self.corps_json()
        if c.execute("SELECT COUNT(*) n FROM appareils").fetchone()["n"] > 0:
            raise Erreur(409, "coffre déjà initialisé : utilise une invitation ou la récupération")
        attendu = jeton_installation() or ""
        if not attendu or not hmac.compare_digest(str(d.get("installation", "")), attendu):
            self.echec(c)
            raise Erreur(401, "jeton d'installation incorrect")
        preuve = str(d.get("preuve_recuperation", ""))
        if len(preuve) < 32:
            raise Erreur(400, "preuve de récupération manquante")
        ecrire_meta(c, "recuperation", h(preuve))
        r = self.nouvel_appareil(c, d.get("nom"), "admin")
        jeton_installation()  # supprime le jeton d'installation
        return self.repondre(200, r)

    def inviter(self, c):
        a = self.appareil(c, admin=True)
        d = self.corps_json()
        role = d.get("role", "technicien")
        if role not in ROLES:
            raise Erreur(400, "rôle inconnu")
        code = secrets.token_urlsafe(12)
        c.execute("INSERT INTO invitations(code_hash, role, nom, expire, cree_par) VALUES(?,?,?,?,?)",
                  (h(code), role, (d.get("nom") or "")[:60], maintenant() + 3600, a["id"]))
        return self.repondre(200, {"code": code, "role": role, "categories": ROLES[role], "expire": maintenant() + 3600})

    def appairer(self, c):
        if self.trop_d_echecs(c):
            raise Erreur(429, "trop d'essais")
        d = self.corps_json()
        i = c.execute("SELECT * FROM invitations WHERE code_hash=? AND utilise=0 AND expire>?", (h(str(d.get("code", ""))), maintenant())).fetchone()
        if not i:
            self.echec(c)
            raise Erreur(401, "invitation inconnue, expirée ou déjà utilisée")
        c.execute("UPDATE invitations SET utilise=1 WHERE code_hash=?", (i["code_hash"],))
        return self.repondre(200, self.nouvel_appareil(c, d.get("nom") or i["nom"], i["role"]))

    def recuperer(self, c):
        if self.trop_d_echecs(c):
            raise Erreur(429, "trop d'essais : réessaie dans 10 minutes")
        d = self.corps_json()
        attendu = meta(c, "recuperation", "")
        if not attendu or not hmac.compare_digest(h(str(d.get("preuve_recuperation", ""))), attendu):
            self.echec(c)
            raise Erreur(401, "code de récupération incorrect")
        journal("Récupération : nouvel appareil administrateur")
        return self.repondre(200, self.nouvel_appareil(c, d.get("nom"), "admin"))

    # -- objets
    def changements(self, c, q):
        a = self.appareil(c)
        depuis = int(q.get("depuis", "0") or 0)
        cats = json.loads(a["categories"])
        lignes = c.execute("SELECT * FROM objets WHERE seq>? ORDER BY seq LIMIT 2000", (depuis,)).fetchall()
        l = [dict(chemin=r["chemin"], categorie=r["categorie"], version=r["version"], sha256=r["sha256"], taille=r["taille"],
                  modifie=r["modifie"], appareil=r["appareil"], supprime=bool(r["supprime"]), seq=r["seq"])
             for r in lignes if r["categorie"] in cats]
        dernier = lignes[-1]["seq"] if lignes else depuis
        return self.repondre(200, {"changements": l, "seq": dernier, "suite": len(lignes) == 2000})

    def objet_autorise(self, c, a, ch):
        o = c.execute("SELECT * FROM objets WHERE chemin=?", (ch,)).fetchone()
        if o and o["categorie"] not in json.loads(a["categories"]):
            raise Erreur(403, "catégorie non autorisée pour cet appareil")
        return o

    def lire(self, c, ch, q):
        a = self.appareil(c)
        o = self.objet_autorise(c, a, ch)
        if not o:
            raise Erreur(404, "inconnu")
        if q.get("version"):
            v = c.execute("SELECT * FROM versions WHERE chemin=? AND version=?", (ch, int(q["version"]))).fetchone()
            if not v or v["supprime"]:
                raise Erreur(404, "version inconnue")
            sha = v["sha256"]
        else:
            if o["supprime"]:
                raise Erreur(410, "supprimé", {"version": o["version"]})
            sha = o["sha256"]
        with open(chemin_blob(sha), "rb") as f:
            octets = f.read()
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(octets)))
        self.send_header("X-Version", str(o["version"]))
        self.end_headers()
        self.wfile.write(octets)

    def ecrire(self, c, ch, q):
        a = self.appareil(c)
        cat = q.get("categorie", "")
        if cat not in CATEGORIES or cat not in json.loads(a["categories"]):
            raise Erreur(403, "catégorie non autorisée pour cet appareil")
        o = self.objet_autorise(c, a, ch)
        base = int(q.get("base", "0") or 0)
        if base != (o["version"] if o else 0):
            raise Erreur(409, "conflit : une version plus récente existe", {"version": o["version"] if o else 0})
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0 or n > MAX_OBJET:
            raise Erreur(413, "taille invalide")
        self.corps_lu = True
        tmp = os.path.join(DATA, "blobs", ".tmp-" + secrets.token_hex(8))
        os.makedirs(os.path.dirname(tmp), exist_ok=True)
        hs = hashlib.sha256()
        reste = n
        with open(tmp, "wb") as f:
            while reste > 0:
                morceau = self.rfile.read(min(reste, 1 << 20))
                if not morceau:
                    break
                hs.update(morceau)
                f.write(morceau)
                reste -= len(morceau)
        if reste:
            os.remove(tmp)
            raise Erreur(400, "envoi interrompu")
        sha = hs.hexdigest()
        dest = chemin_blob(sha)
        os.makedirs(os.path.dirname(dest), exist_ok=True)
        if os.path.exists(dest):
            os.remove(tmp)
        else:
            os.replace(tmp, dest)
        with _verrou:
            return self.enregistrer(c, a, ch, cat, base, sha, n)

    def enregistrer(self, c, a, ch, cat, base, sha, n):
        # Revérifié sous verrou : un autre appareil a pu écrire pendant l'envoi.
        o = c.execute("SELECT version FROM objets WHERE chemin=?", (ch,)).fetchone()
        actuelle = o["version"] if o else 0
        if base != actuelle:
            supprimer_blob_si_orphelin(c, sha)
            raise Erreur(409, "conflit : une version plus récente existe", {"version": actuelle})
        version = actuelle + 1
        seq = prochain_seq(c)
        c.execute("INSERT INTO objets(chemin, categorie, version, sha256, taille, modifie, appareil, supprime, seq) VALUES(?,?,?,?,?,?,?,0,?) "
                  "ON CONFLICT(chemin) DO UPDATE SET categorie=excluded.categorie, version=excluded.version, sha256=excluded.sha256, "
                  "taille=excluded.taille, modifie=excluded.modifie, appareil=excluded.appareil, supprime=0, seq=excluded.seq",
                  (ch, cat, version, sha, n, maintenant(), a["id"], seq))
        c.execute("INSERT INTO versions(chemin, version, sha256, taille, modifie, appareil, supprime) VALUES(?,?,?,?,?,?,0)",
                  (ch, version, sha, n, maintenant(), a["id"]))
        self.elaguer(c, ch)
        return self.repondre(200, {"version": version, "seq": seq, "sha256": sha})

    def supprimer(self, c, ch, q):
        with _verrou:
            return self.supprimer_verrouille(c, ch, q)

    def supprimer_verrouille(self, c, ch, q):
        a = self.appareil(c)
        o = self.objet_autorise(c, a, ch)
        if not o or o["supprime"]:
            return self.repondre(200, {"ok": True})
        base = int(q.get("base", "0") or 0)
        if base != o["version"]:
            raise Erreur(409, "conflit : une version plus récente existe", {"version": o["version"]})
        version = o["version"] + 1
        seq = prochain_seq(c)
        c.execute("UPDATE objets SET version=?, supprime=1, modifie=?, appareil=?, seq=? WHERE chemin=?", (version, maintenant(), a["id"], seq, ch))
        c.execute("INSERT INTO versions(chemin, version, sha256, taille, modifie, appareil, supprime) VALUES(?,?,?,?,?,?,1)",
                  (ch, version, o["sha256"], 0, maintenant(), a["id"]))
        return self.repondre(200, {"version": version, "seq": seq})

    def elaguer(self, c, ch):
        vieilles = c.execute("SELECT version, sha256 FROM versions WHERE chemin=? ORDER BY version DESC LIMIT -1 OFFSET ?", (ch, GARDER_VERSIONS)).fetchall()
        for v in vieilles:
            c.execute("DELETE FROM versions WHERE chemin=? AND version=?", (ch, v["version"]))
            supprimer_blob_si_orphelin(c, v["sha256"])


# ---------------------------------------------------------------------------------------------------------- entretien


def attendre_la_nuit(heure=3):
    t = time.localtime()
    cible = time.mktime((t.tm_year, t.tm_mon, t.tm_mday, heure, 0, 0, 0, 0, -1))
    if cible <= time.time():
        cible += 86400
    time.sleep(max(60, cible - time.time()))


def entretien():
    """Chaque nuit (3 h) : corbeille vidée après 30 jours, sauvegarde (base + fichiers chiffrés), anciennes sauvegardes effacées."""
    premiere = True
    while True:
        if not premiere:
            attendre_la_nuit()
        premiere = False
        try:
            with _verrou:
                c = db()
                try:
                    limite = maintenant() - CORBEILLE_JOURS * 86400
                    for o in c.execute("SELECT chemin FROM objets WHERE supprime=1 AND modifie<?", (limite,)).fetchall():
                        shas = [r["sha256"] for r in c.execute("SELECT sha256 FROM versions WHERE chemin=?", (o["chemin"],))]
                        c.execute("DELETE FROM versions WHERE chemin=?", (o["chemin"],))
                        c.execute("UPDATE objets SET sha256='', taille=0 WHERE chemin=?", (o["chemin"],))
                        for s in shas:
                            supprimer_blob_si_orphelin(c, s)
                    sauvegarder(c)
                finally:
                    c.close()
        except Exception as e:  # noqa
            journal("Entretien impossible :", repr(e))


def sauvegarder(c):
    os.makedirs(os.path.join(SAUVEGARDES, "blobs"), exist_ok=True)
    jour = time.strftime("%Y%m%d")
    dest = sqlite3.connect(os.path.join(SAUVEGARDES, f"coffre-{jour}.db"))
    c.backup(dest)
    dest.close()
    # Fichiers chiffrés : copie incrémentale (un fichier ne change jamais, son nom est son empreinte).
    src = os.path.join(DATA, "blobs")
    for racine, _, fichiers in os.walk(src):
        for nom in fichiers:
            if nom.startswith(".tmp-"):
                continue
            rel = os.path.relpath(os.path.join(racine, nom), src)
            cible = os.path.join(SAUVEGARDES, "blobs", rel)
            if not os.path.exists(cible):
                os.makedirs(os.path.dirname(cible), exist_ok=True)
                shutil.copy2(os.path.join(racine, nom), cible)
    for vieux in sorted(f for f in os.listdir(SAUVEGARDES) if f.startswith("coffre-") and f.endswith(".db"))[:-GARDER_SAUVEGARDES]:
        os.remove(os.path.join(SAUVEGARDES, vieux))
    ecrire_meta(c, "derniere_sauvegarde", time.strftime("%Y-%m-%d %H:%M"))
    journal("Sauvegarde faite dans", SAUVEGARDES)


def main():
    os.makedirs(os.path.join(DATA, "blobs"), exist_ok=True)
    initialiser_base()
    cert, cle, empreinte = preparer_tls()
    jeton = jeton_installation()
    journal(f"Coffre-fort JARVIS {VERSION} — port {PORT} — empreinte {empreinte}")
    if jeton:
        journal("En attente du premier appareil (jeton d'installation dans", os.path.join(DATA, "installation.txt") + ")")
    threading.Thread(target=entretien, daemon=True).start()
    ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    ctx.minimum_version = ssl.TLSVersion.TLSv1_2
    ctx.load_cert_chain(cert, cle)

    class Serveur(ThreadingHTTPServer):
        daemon_threads = True

        def get_request(self):
            sock, adr = self.socket.accept()
            return ctx.wrap_socket(sock, server_side=True, do_handshake_on_connect=False), adr

        def handle_error(self, request, client_address):
            pass  # connexions interrompues, poignées de main ratées : sans intérêt pour le journal

    Serveur(("0.0.0.0", PORT), Gestionnaire).serve_forever()


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "--sauvegarder":
        initialiser_base()
        c = db()
        sauvegarder(c)
        c.close()
    else:
        main()
