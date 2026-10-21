"""Fixed optional topic catalogue. IDs are stable; None means Sin tema."""
from html import escape
import re
import unicodedata

TOPIC_TITLES = (
    "FuncionPublica",
    "Igualdad.pdf",
    "PRL y EPI trampeado Cecop.pdf",
    "DL 1 2006.pdf",
    "SNPC.pdf",
    "PLATERCAM.pdf",
    "CTE DB SI.pdf",
    "Territorio y Red de infraestructuras de la CM.pdf",
    "Organización del CBCM.pdf",
    "Mecánica básica.pdf",
    "Comunicaciones.pdf",
    "SVB I (trampeado de Cecop).pdf",
    "SVB II.pdf",
    "Psicología para intervinientes.pdf",
    "Hidráulica.pdf",
    "Naturaleza del fuego, métodos de extinción y agentes extintores.pdf",
    "Incendios en la edificación I.pdf",
    "Incendios en la edificación II.pdf",
    "Incendios de Vegetación I.pdf",
    "Incendios de Vegetación II.pdf",
    "RBQ I.pdf",
    "RBQ II.pdf",
    "Construcción I.pdf",
    "Construcción II.pdf",
    "RAT I.pdf",
    "RAT II.pdf",
    "Trabajos verticales.pdf",
    "Riesgo Eléctrico.pdf",
    "Suministros de gas.pdf",
    "Intervenciones con abejas.pdf",
    "Ascensores y aperturas forzosas.pdf",
    "Apeos de arbolado.pdf",
    "Actuación en Conductas Autolíticas.pdf",
    "Rescate acuático.pdf",
    "Conducción segura en emergencias.pdf",
)
TOPICS = {i: f"Tema {i:02d}. {title}" for i, title in enumerate(TOPIC_TITLES, 1)}


TOPIC_ALIASES = {
    1: ("funcion publica", "funcionpublica"),
    2: ("igualdad",),
    3: ("prl y epi trampeado cecop",),
    4: ("decreto legislativo 1 2006", "dl 1 2006", "dl 1 06"),
    5: ("snpc",),
    6: ("platercam",),
    7: ("cte db si",),
    8: ("territorio y red de infraestructuras de la cm",),
    9: ("organizacion del cbcm",),
    10: ("mecanica basica",),
    11: ("comunicaciones",),
    12: ("svb i trampeado de cecop", "svb 1 trampeado de cecop"),
    13: ("svb ii", "svb 2"),
    14: ("psicologia para intervinientes",),
    15: ("hidraulica",),
    16: ("naturaleza del fuego metodos de extincion y agentes extintores",),
    17: ("incendios en la edificacion i", "incendios en la edificacion 1"),
    18: ("incendios en la edificacion ii", "incendios en la edificacion 2"),
    19: ("incendios de vegetacion i", "incendios de vegetacion 1"),
    20: ("incendios de vegetacion ii", "incendios de vegetacion 2"),
    21: ("rbq i", "rbq 1"),
    22: ("rbq ii", "rbq 2"),
    23: ("construccion i", "construccion 1"),
    24: ("construccion ii", "construccion 2"),
    25: ("rat i", "rat 1"),
    26: ("rat ii", "rat 2"),
    27: ("trabajos verticales",),
    28: ("riesgo electrico",),
    29: ("suministros de gas",),
    30: ("intervenciones con abejas",),
    31: ("ascensores y aperturas forzosas",),
    32: ("apeos de arbolado",),
    33: ("actuacion en conductas autoliticas",),
    34: ("rescate acuatico",),
    35: ("conduccion segura en emergencias",),
}


def normalize_topic_text(value):
    value = unicodedata.normalize("NFKD", str(value or "").casefold())
    value = "".join(char for char in value if not unicodedata.combining(char))
    return re.sub(r"[^a-z0-9]+", " ", value).strip()


def detect_topic_id(value):
    """Map explicit source-page topic labels to the fixed catalogue; never guess."""
    text = f" {normalize_topic_text(value)} "
    aliases = []
    for topic_id, title in TOPICS.items():
        aliases.append((normalize_topic_text(title.removeprefix(f"Tema {topic_id:02d}.").removesuffix(".pdf")), topic_id))
    for topic_id, values in TOPIC_ALIASES.items():
        aliases.extend((normalize_topic_text(alias), topic_id) for alias in values)
    for alias, topic_id in sorted(aliases, key=lambda item: len(item[0]), reverse=True):
        if alias and f" {alias} " in text:
            return topic_id
    return None


def parse_topic(value):
    if value is None or value == "":
        return None
    if not isinstance(value, str) or value not in {str(i) for i in TOPICS}:
        raise ValueError("Tema no válido. Elige uno de la lista o Sin tema.")
    return int(value)


def topic_select(selected=None, *, name="topic_id", include_all=False, counts=None):
    if counts is not None:
        total = sum(counts.values())
        choices = [("", f"Todos ({total})")]
        if counts.get(None, 0):
            choices.append(("none", f"Sin tema ({counts[None]})"))
        choices += [
            (str(i), f"{title} ({counts[i]})")
            for i, title in TOPICS.items() if counts.get(i, 0)
        ]
    else:
        choices = ([("", "Todos"), ("none", "Sin tema")] if include_all
                   else [("", "Sin tema")])
        choices += [(str(i), title) for i, title in TOPICS.items()]
    value = "" if selected is None else str(selected)
    options = "".join(
        f"<option value='{key}'{' selected' if key == value else ''}>{escape(label)}</option>"
        for key, label in choices
    )
    return (f"<select name='{escape(name)}' aria-label='Tema' "
            f"style='max-width:100%;padding:8px;border:1px solid #d1d5db;border-radius:8px'>{options}</select>")


def topic_filter(value):
    """Return a fixed SQL fragment and parameters, never interpolate user input."""
    if value == "none":
        return " AND topic_id IS NULL", ()
    topic_id = parse_topic(value)
    return (" AND topic_id=%s", (topic_id,)) if topic_id else ("", ())


def migrate_topics(connection):
    # Nullable, no default/backfill or index/table scan. Bound the DDL lock wait.
    connection.execute("SET LOCAL lock_timeout = '3s'")
    connection.execute("""ALTER TABLE questions ADD COLUMN IF NOT EXISTS topic_id
        SMALLINT CONSTRAINT questions_topic_id_check CHECK (topic_id BETWEEN 1 AND 35)""")
    connection.execute("ALTER TABLE questions ADD COLUMN IF NOT EXISTS source_url TEXT")
