from __future__ import annotations

import json
import os
import random
import re
import sqlite3
import tempfile
from pathlib import Path
from html import escape

import cv2
import numpy as np
import psycopg
import pytesseract
from flask import Flask, flash, redirect, render_template_string, request, session, url_for
from psycopg.rows import dict_row

app = Flask(__name__)
app.secret_key = os.environ.get("SECRET_KEY", "CAMBIA_ESTA_CLAVE")
app.config["MAX_CONTENT_LENGTH"] = 12 * 1024 * 1024
DATABASE_URL = os.environ.get("DATABASE_URL", "").strip()
APP_PASSWORD = os.environ.get("APP_PASSWORD", "").strip()

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
input[type=password]{width:100%;box-sizing:border-box;padding:12px;border:1px solid #d1d5db;border-radius:10px;font-size:17px}
@media(max-width:600px){.card{padding:20px}.btn{width:100%;text-align:center}}
"""


def page(body: str, title="Mis preguntas", **ctx):
    template = """<!doctype html><html lang='es'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'><title>""" + title + """</title><style>""" + CSS + """</style></head><body><main>
    {% if session.get('logged_in') %}<nav><a href='""" + url_for('index') + """'>Inicio</a><a href='""" + url_for('upload') + """'>Añadir</a><a href='""" + url_for('questions') + """'>Guardadas</a><a href='""" + url_for('logout') + """'>Salir</a></nav>{% endif %}
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
        c.execute("""CREATE TABLE IF NOT EXISTS questions(
            id BIGSERIAL PRIMARY KEY,
            question TEXT NOT NULL,
            options_json TEXT NOT NULL,
            correct_index INTEGER NOT NULL,
            created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
        )""")


def clean(s: str) -> str:
    return re.sub(r"\s+", " ", s.replace("\u00a0", " ")).strip()


def parse_options(value):
    if isinstance(value, list):
        return value
    if isinstance(value, str):
        return json.loads(value)
    raise TypeError(f"Formato inesperado para options_json: {type(value).__name__}")


def ocr_lines(image):
    # No usamos DATAFRAME: no hace falta pandas.
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


def all_ids():
    with db() as c:
        ids = [int(r["id"]) for r in c.execute("SELECT id FROM questions").fetchall()]
    random.shuffle(ids)
    return ids


def get_question(qid):
    with db() as c:
        return c.execute("SELECT * FROM questions WHERE id=%s", (qid,)).fetchone()


def logged_in():
    return bool(session.get("logged_in"))


@app.before_request
def require_login():
    if not APP_PASSWORD:
        return None
    allowed = {"login", "static"}
    if request.endpoint not in allowed and not logged_in():
        return redirect(url_for("login"))
    return None


@app.route("/login", methods=["GET", "POST"])
def login():
    if not APP_PASSWORD:
        return redirect(url_for("index"))
    if request.method == "POST":
        if request.form.get("password", "") == APP_PASSWORD:
            session["logged_in"] = True
            return redirect(url_for("index"))
        flash("Contraseña incorrecta.", "error")
    body = """<section class='card'><h1>Preguntas</h1><p>Introduce la contraseña para entrar.</p><form method='post'><input type='password' name='password' required autofocus><div class='actions'><button class='btn primary' type='submit'>Entrar</button></div></form></section>"""
    return page(body, "Entrar")


@app.route("/logout")
def logout():
    session.clear()
    return redirect(url_for("login"))


@app.route("/")
def index():
    with db() as c:
        count = c.execute("SELECT COUNT(*) AS n FROM questions").fetchone()["n"]
    body = f"""<section class='card'><h1>Mis preguntas</h1><p class='big'>{count} guardadas</p><div class='actions'>
    <a class='btn primary' href='{url_for('upload')}'>Añadir captura</a><a class='btn' href='{url_for('practice',mode='random')}'>Pregunta aleatoria</a><a class='btn' href='{url_for('practice',mode='all')}'>Hacerlas todas</a></div></section>
    <p class='muted'>Preguntas compartidas online.</p>"""
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

    with db() as c:
        if c.execute("SELECT id FROM questions WHERE question=%s LIMIT 1", (question,)).fetchone():
            flash("Esa pregunta ya estaba guardada.", "error")
            return redirect(url_for("index"))
        c.execute("INSERT INTO questions(question,options_json,correct_index) VALUES(%s,%s,%s)",
                  (question, json.dumps(options, ensure_ascii=False), correct))
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
        with db() as c:
            row = c.execute("SELECT id FROM questions ORDER BY RANDOM() LIMIT 1").fetchone()
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
    if session.get("mode") == "all":
        session["queue"] = [int(x) for x in session.get("queue", []) if int(x) != qid]
    return redirect(url_for("next_question"))


@app.post("/delete/<int:qid>")
def delete(qid):
    with db() as c:
        c.execute("DELETE FROM questions WHERE id=%s", (qid,))
    if session.get("mode") == "all":
        session["queue"] = [int(x) for x in session.get("queue", []) if int(x) != qid]
    return redirect(url_for("next_question"))


@app.route("/questions")
def questions():
    with db() as c:
        rows = c.execute("SELECT * FROM questions ORDER BY id DESC").fetchall()
    if not rows:
        return page("<section class='card'><h1>Guardadas</h1><p>No hay preguntas todavía.</p></section>")
    items=[]
    for q in rows:
        opts=parse_options(q["options_json"]); ok=opts[int(q["correct_index"])]
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
