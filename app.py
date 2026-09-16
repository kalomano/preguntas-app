from __future__ import annotations

import json
import os
import random
import re
import tempfile
from html import escape

import cv2
import numpy as np
import psycopg
import pytesseract
from flask import Flask, flash, redirect, render_template_string, request, session, url_for
from psycopg.rows import dict_row

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "CAMBIA-ESTA-CLAVE")
app.config["MAX_CONTENT_LENGTH"] = 12 * 1024 * 1024
DATABASE_URL = os.environ.get("DATABASE_URL", "").strip()

CSS = """
body{font-family:system-ui,-apple-system,Segoe UI,sans-serif;background:#f5f7fb;color:#111827;margin:0}
main{width:min(900px,calc(100% - 28px));margin:auto;padding:20px 0 60px}
nav{display:flex;gap:18px;flex-wrap:wrap;margin-bottom:18px}nav a{color:#374151;text-decoration:none;font-weight:700}
.card{background:#fff;border:1px solid #e5e7eb;border-radius:18px;padding:28px;box-shadow:0 8px 28px rgba(0,0,0,.05)}
h1,h2{line-height:1.3}p{font-size:17px;line-height:1.55}.big{font-size:42px;font-weight:800;margin:0 0 20px}
.actions{display:flex;gap:10px;flex-wrap:wrap;margin-top:22px}.btn{display:inline-block;padding:11px 15px;border-radius:10px;border:1px solid #d1d5db;background:white;color:#111827;font-weight:800;text-decoration:none;cursor:pointer}
.primary{background:#111827;color:white}.danger{background:#b91c1c;color:white}.flash{padding:12px 14px;border-radius:10px;margin:0 0 14px}.success{background:#dcfce7;color:#166534}.error{background:#fee2e2;color:#991b1b}
.upload{border:2px dashed #cbd5e1;border-radius:14px;padding:30px;text-align:center;margin:20px 0}.answers{display:grid;gap:12px;margin:20px 0}.answer{display:block;border:1px solid #d1d5db;border-radius:12px;padding:15px;background:white;cursor:pointer}.answer.correct{background:#dcfce7;border-color:#22c55e}.answer.selected{background:#fee2e2;border-color:#ef4444}
.badge{display:inline-block;background:#eef2ff;color:#3730a3;border-radius:999px;padding:5px 10px;font-weight:800;font-size:13px}.result{font-size:28px;font-weight:900}.ok{color:#15803d}.bad{color:#b91c1c}.item{border-top:1px solid #e5e7eb;padding:18px 0}.item:first-child{border-top:0}.muted{color:#6b7280}
input[type=text]{width:100%;box-sizing:border-box;padding:12px;border:1px solid #d1d5db;border-radius:10px;font-size:17px}
@media(max-width:600px){.card{padding:20px}.btn{width:100%;text-align:center}}
"""


def page(body: str, title="Mis preguntas", **ctx):
    user_name = ctx.pop("user_name", None)
    template = """<!doctype html><html lang='es'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>""" + title + """</title><style>""" + CSS + """</style></head><body><main>
    {% if session.get('user_id') %}<nav><a href='""" + url_for('index') + """'>Inicio</a><a href='""" + url_for('upload') + """'>Añadir</a><a href='""" + url_for('questions') + """'>Guardadas</a><a href='""" + url_for('users') + """'>Cambiar usuario</a></nav>{% endif %}
    {% with messages = get_flashed_messages(with_categories=true) %}{% for cat,msg in messages %}<div class='flash {{cat}}'>{{msg}}</div>{% endfor %}{% endwith %}
    """ + body + """
    </main></body></html>"""
    return render_template_string(template, **ctx)


def db():
    if not DATABASE_URL:
        raise RuntimeError("Falta la variable DATABASE_URL.")
    return psycopg.connect(DATABASE_URL, row_factory=dict_row, connect_timeout=10)


def init_db():
    with db() as c:
        c.execute("""CREATE TABLE IF NOT EXISTS users(
            id BIGSERIAL PRIMARY KEY,
            name TEXT NOT NULL UNIQUE,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")
        c.execute("""CREATE TABLE IF NOT EXISTS questions(
            id BIGSERIAL PRIMARY KEY,
            question TEXT NOT NULL,
            options_json TEXT NOT NULL,
            correct_index INTEGER NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")
        c.execute("ALTER TABLE questions ADD COLUMN IF NOT EXISTS user_id BIGINT")

        # Preguntas antiguas: se conservan y se asignan a un usuario inicial.
        old_user = c.execute(
            "SELECT id FROM users WHERE name=%s LIMIT 1", ("Sin asignar",)
        ).fetchone()
        if not old_user:
            old_user = c.execute(
                "INSERT INTO users(name) VALUES(%s) RETURNING id", ("Sin asignar",)
            ).fetchone()
        c.execute(
            "UPDATE questions SET user_id=%s WHERE user_id IS NULL", (old_user["id"],)
        )
        c.execute("ALTER TABLE questions ALTER COLUMN user_id SET NOT NULL")
        c.execute("""DO $$
        BEGIN
            IF NOT EXISTS (
                SELECT 1 FROM pg_constraint WHERE conname = 'questions_user_id_fkey'
            ) THEN
                ALTER TABLE questions
                ADD CONSTRAINT questions_user_id_fkey
                FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
            END IF;
        END $$""")
        c.execute("CREATE INDEX IF NOT EXISTS questions_user_id_idx ON questions(user_id)")


def current_user_id():
    try:
        return int(session["user_id"])
    except (KeyError, TypeError, ValueError):
        return None


def current_user_name():
    uid = current_user_id()
    if uid is None:
        return None
    with db() as c:
        row = c.execute("SELECT name FROM users WHERE id=%s", (uid,)).fetchone()
    if not row:
        session.pop("user_id", None)
        return None
    return row["name"]


def clean(s: str) -> str:
    return re.sub(r"\s+", " ", s.replace("\u00a0", " ")).strip()


def ocr_lines(image):
    data = pytesseract.image_to_data(image, lang="spa+eng", config="--psm 11", output_type=pytesseract.Output.DICT)
    groups = {}
    total = len(data.get("text", []))
    for i in range(total):
        try:
            conf = float(data["conf"][i])
        except (ValueError, TypeError):
            conf = -1
        if conf < 20:
            continue
        text = clean(str(data["text"][i]))
        if not text:
            continue
        key = (int(data["block_num"][i]), int(data["par_num"][i]), int(data["line_num"][i]))
        groups.setdefault(key, []).append({
            "text": text,
            "left": int(data["left"][i]),
            "top": int(data["top"][i]),
            "height": int(data["height"][i]),
        })
    lines = []
    for words in groups.values():
        words = sorted(words, key=lambda x: x["left"])
        lines.append({
            "text": clean(" ".join(x["text"] for x in words)),
            "top": min(x["top"] for x in words),
            "bottom": max(x["top"] + x["height"] for x in words),
        })
    return sorted(lines, key=lambda x: (x["top"], x["text"]))


def parse_capture(path: str):
    image = cv2.imread(path)
    if image is None:
        raise ValueError("No se pudo abrir la imagen.")
    lines = ocr_lines(image)
    pat = re.compile(r"^([a-dA-D])\s*[)\.\-:]\s*(.*)$")
    starts = []
    for i, line in enumerate(lines):
        m = pat.match(line["text"])
        if m:
            starts.append((i, m.group(1).lower(), m.group(2).strip(), line))
    if len(starts) < 2:
        raise ValueError("No he detectado las respuestas a), b), c), etc.")

    q_parts = []
    for line in lines[:starts[0][0]]:
        t = re.sub(r"^\d+\.\s*", "", line["text"])
        if t:
            q_parts.append(t)
    question = clean(" ".join(q_parts))
    if not question:
        raise ValueError("No he podido extraer la pregunta.")

    options = []
    for n, (idx, letter, first, line) in enumerate(starts):
        end = starts[n + 1][0] if n + 1 < len(starts) else len(lines)
        top, bottom = line["top"], line["bottom"]
        pieces = [first] if first else []
        for extra in lines[idx + 1:end]:
            pieces.append(extra["text"])
            top = min(top, extra["top"])
            bottom = max(bottom, extra["bottom"])
        options.append({"letter": letter, "text": clean(" ".join(pieces)), "top": top, "bottom": bottom})

    hsv = cv2.cvtColor(image, cv2.COLOR_BGR2HSV)
    mask = cv2.inRange(hsv, np.array([35, 20, 140]), np.array([95, 255, 255]))
    n_labels, _, stats, _ = cv2.connectedComponentsWithStats(mask)
    greens = []
    h, w = image.shape[:2]
    for i in range(1, n_labels):
        x, y, ww, hh, area = stats[i]
        if area > h * w * 0.02 and ww > w * 0.5 and hh > 25:
            greens.append((int(y), int(y + hh)))
    if not greens:
        raise ValueError("No he encontrado la respuesta correcta marcada en verde.")
    gy1, gy2 = max(greens, key=lambda x: x[1] - x[0])
    overlaps = [max(0, min(o["bottom"], gy2) - max(o["top"], gy1)) for o in options]
    correct = overlaps.index(max(overlaps))
    return question, [{"letter": o["letter"], "text": o["text"]} for o in options], correct


def parse_options(value):
    if isinstance(value, list):
        return value
    if isinstance(value, (str, bytes, bytearray)):
        return json.loads(value)
    raise TypeError(f"Formato inesperado para options_json: {type(value).__name__}")


def all_ids():
    uid = current_user_id()
    if uid is None:
        return []
    with db() as c:
        ids = [int(r["id"]) for r in c.execute(
            "SELECT id FROM questions WHERE user_id=%s", (uid,)
        ).fetchall()]
    random.shuffle(ids)
    return ids


def get_question(qid):
    uid = current_user_id()
    if uid is None:
        return None
    with db() as c:
        return c.execute(
            "SELECT * FROM questions WHERE id=%s AND user_id=%s", (qid, uid)
        ).fetchone()


def get_users():
    with db() as c:
        return c.execute("SELECT id,name FROM users ORDER BY LOWER(name), id").fetchall()


@app.before_request
def require_user():
    allowed = {"users", "select_user", "static"}
    if request.endpoint not in allowed and current_user_id() is None:
        return redirect(url_for("users"))
    return None


@app.route("/users")
def users():
    user_rows = get_users()
    body = """<section class='card'><h1>¿Quién eres?</h1><p>Elige tu usuario. Cada usuario tiene sus propias preguntas.</p>
    <form method='post' action='""" + url_for("select_user") + """'><div class='answers'>"""
    for u in user_rows:
        body += (
            "<label class='answer'><input type='radio' name='user_id' value='"
            + str(u["id"])
            + "' required> <strong>"
            + escape(u["name"])
            + "</strong></label>"
        )
    body += """</div><button class='btn primary' type='submit'>Entrar con este usuario</button></form>
    <h2 style='margin-top:30px'>Crear usuario nuevo</h2>
    <form method='post' action='""" + url_for("create_user") + """'><input type='text' name='name' maxlength='80' placeholder='Tu nombre' required autofocus><div class='actions'><button class='btn' type='submit'>Crear y entrar</button></div></form>
    </section>"""
    return page(body, "Elegir usuario")


@app.post("/select-user")
def select_user():
    try:
        uid = int(request.form.get("user_id", ""))
    except ValueError:
        flash("Selecciona un usuario.", "error")
        return redirect(url_for("users"))
    with db() as c:
        row = c.execute("SELECT id FROM users WHERE id=%s", (uid,)).fetchone()
    if not row:
        flash("Ese usuario no existe.", "error")
        return redirect(url_for("users"))
    session.clear()
    session["user_id"] = uid
    return redirect(url_for("index"))


@app.post("/create-user")
def create_user():
    name = clean(request.form.get("name", ""))
    if not name:
        flash("Escribe un nombre.", "error")
        return redirect(url_for("users"))
    if len(name) > 80:
        flash("El nombre es demasiado largo.", "error")
        return redirect(url_for("users"))
    with db() as c:
        existing = c.execute("SELECT id,name FROM users WHERE LOWER(name)=LOWER(%s) LIMIT 1", (name,)).fetchone()
        if existing:
            session.clear()
            session["user_id"] = existing["id"]
            flash("Ese usuario ya existía. Has entrado con él.", "success")
            return redirect(url_for("index"))
        row = c.execute("INSERT INTO users(name) VALUES(%s) RETURNING id", (name,)).fetchone()
    session.clear()
    session["user_id"] = row["id"]
    flash(f"Usuario {name} creado.", "success")
    return redirect(url_for("index"))


@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("users"))


@app.route("/")
def index():
    uid = current_user_id()
    user_name = current_user_name()
    with db() as c:
        count = c.execute("SELECT COUNT(*) AS n FROM questions WHERE user_id=%s", (uid,)).fetchone()["n"]
    body = f"""<section class='card'><span class='badge'>Usuario: {escape(user_name)}</span><h1>Mis preguntas</h1><p class='big'>{count} guardadas</p><div class='actions'>
    <a class='btn primary' href='{url_for('upload')}'>Añadir captura</a><a class='btn' href='{url_for('practice',mode='random')}'>Pregunta aleatoria</a><a class='btn' href='{url_for('practice',mode='all')}'>Hacerlas todas</a></div></section>
    <p class='muted'>Tus preguntas son independientes de las de los demás usuarios.</p>"""
    return page(body)


@app.route("/upload", methods=["GET", "POST"])
def upload():
    if request.method == "GET":
        body = """<section class='card'><h1>Añadir captura</h1><p>Sube una imagen como la que has enviado. La app lee la pregunta, las respuestas y usa el verde para detectar la correcta.</p>
        <form method='post' enctype='multipart/form-data'><div class='upload'><input type='file' name='image' accept='image/png,image/jpeg,image/webp' required></div><button class='btn primary' type='submit'>Analizar y guardar</button></form></section>"""
        return page(body)

    f = request.files.get("image")
    if not f or not f.filename:
        flash("Selecciona una imagen.", "error")
        return redirect(url_for("upload"))
    ext = f.filename.rsplit(".", 1)[-1].lower()
    if ext not in {"png", "jpg", "jpeg", "webp"}:
        flash("Formato no válido. Usa PNG, JPG, JPEG o WEBP.", "error")
        return redirect(url_for("upload"))

    fd, temp_name = tempfile.mkstemp(suffix="." + ext)
    os.close(fd)
    try:
        f.save(temp_name)
        question, options, correct = parse_capture(temp_name)
    except Exception as e:
        flash(f"No pude analizar la imagen: {e}", "error")
        return redirect(url_for("upload"))
    finally:
        try:
            os.remove(temp_name)
        except FileNotFoundError:
            pass

    uid = current_user_id()
    with db() as c:
        if c.execute(
            "SELECT id FROM questions WHERE user_id=%s AND question=%s LIMIT 1",
            (uid, question),
        ).fetchone():
            flash("Esa pregunta ya estaba guardada para este usuario.", "error")
            return redirect(url_for("index"))
        c.execute(
            "INSERT INTO questions(question,options_json,correct_index,user_id) VALUES(%s,%s,%s,%s)",
            (question, json.dumps(options, ensure_ascii=False), correct, uid),
        )
    flash(f"Guardada. Correcta: {options[correct]['letter']}) {options[correct]['text']}", "success")
    return redirect(url_for("index"))


@app.route("/practice")
def practice():
    mode = request.args.get("mode", "random")
    if mode not in {"random", "all"}:
        mode = "random"
    session["mode"] = mode
    if mode == "all":
        session["queue"] = all_ids()
    else:
        session.pop("queue", None)
    return redirect(url_for("next_question"))


@app.route("/next")
def next_question():
    mode = session.get("mode", "random")
    if mode == "all":
        queue = [int(x) for x in session.get("queue", [])]
        queue = [x for x in queue if get_question(x) is not None]
        session["queue"] = queue
        if not queue:
            return page("<section class='card'><h1>Has terminado</h1><p>No quedan preguntas en esta tanda.</p><a class='btn primary' href='" + url_for('practice', mode='all') + "'>Repetir todas</a></section>")
        qid = queue[0]
    else:
        uid = current_user_id()
        with db() as c:
            row = c.execute(
                "SELECT id FROM questions WHERE user_id=%s ORDER BY RANDOM() LIMIT 1",
                (uid,),
            ).fetchone()
        if not row:
            flash("No hay preguntas guardadas todavía.", "error")
            return redirect(url_for("index"))
        qid = int(row["id"])

    q = get_question(qid)
    options = parse_options(q["options_json"])
    answers = "".join(f"<label class='answer'><input type='radio' name='answer' value='{i}' required> <strong>{escape(o['letter'])})</strong> {escape(o['text'])}</label>" for i,o in enumerate(options))
    body = f"""<section class='card'><span class='badge'>{'Todas' if mode=='all' else 'Aleatoria'}</span><h1>{escape(q['question'])}</h1>
    <form method='post' action='{url_for('answer',qid=qid)}'><div class='answers'>{answers}</div><button class='btn primary' type='submit'>Responder</button></form></section>"""
    return page(body)


@app.route("/answer/<int:qid>", methods=["POST"])
def answer(qid):
    q = get_question(qid)
    if not q:
        return redirect(url_for("index"))
    try:
        selected = int(request.form["answer"])
    except Exception:
        flash("Selecciona una respuesta.", "error")
        return redirect(url_for("next_question"))
    options = parse_options(q["options_json"])
    correct = int(q["correct_index"])
    rows = []
    for i,o in enumerate(options):
        cls = "correct" if i == correct else ("selected" if i == selected else "")
        rows.append(f"<div class='answer {cls}'><strong>{escape(o['letter'])})</strong> {escape(o['text'])}</div>")
    estado = "Correcta" if selected == correct else "Incorrecta"
    estado_cls = "ok" if selected == correct else "bad"
    body = f"""<section class='card'><div class='result {estado_cls}'>{estado}</div><h2>{escape(q['question'])}</h2><div class='answers'>{''.join(rows)}</div>
    <p>Respuesta correcta: <strong>{escape(options[correct]['letter'])}) {escape(options[correct]['text'])}</strong></p>
    <div class='actions'><form method='post' action='{url_for('keep',qid=qid)}'><button class='btn primary'>Mantener y siguiente</button></form>
    <form method='post' action='{url_for('delete',qid=qid)}' onsubmit='return confirm("¿Eliminar definitivamente esta pregunta?")'><button class='btn danger'>Eliminar y siguiente</button></form>
    <a class='btn' href='{url_for('index')}'>Salir</a></div></section>"""
    return page(body)


@app.post("/keep/<int:qid>")
def keep(qid):
    if not get_question(qid):
        return redirect(url_for("index"))
    if session.get("mode") == "all":
        session["queue"] = [int(x) for x in session.get("queue", []) if int(x) != qid]
    return redirect(url_for("next_question"))


@app.post("/delete/<int:qid>")
def delete(qid):
    uid = current_user_id()
    with db() as c:
        c.execute("DELETE FROM questions WHERE id=%s AND user_id=%s", (qid, uid))
    if session.get("mode") == "all":
        session["queue"] = [int(x) for x in session.get("queue", []) if int(x) != qid]
    return redirect(url_for("next_question"))


@app.route("/questions")
def questions():
    uid = current_user_id()
    with db() as c:
        rows = c.execute(
            "SELECT * FROM questions WHERE user_id=%s ORDER BY id DESC", (uid,)
        ).fetchall()
    if not rows:
        return page("<section class='card'><h1>Guardadas</h1><p>No hay preguntas todavía para este usuario.</p></section>")
    items = []
    for q in rows:
        opts = parse_options(q["options_json"])
        ok = opts[int(q["correct_index"])]
        items.append(f"<article class='item'><h3>{escape(q['question'])}</h3><p>Correcta: <strong>{escape(ok['letter'])}) {escape(ok['text'])}</strong></p></article>")
    return page("<section class='card'><h1>Guardadas</h1>" + "".join(items) + "</section>")


@app.errorhandler(413)
def too_large(_):
    flash("La imagen es demasiado grande. Máximo 12 MB.", "error")
    return redirect(url_for("upload"))


try:
    if DATABASE_URL:
        init_db()
except Exception as exc:
    print("No se pudo inicializar la base de datos al arrancar:", exc)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "10000")), debug=False)
