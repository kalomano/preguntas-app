"""Fixed optional topic catalogue. IDs are stable; None means Sin tema."""
from html import escape

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


def parse_topic(value):
    if value is None or value == "":
        return None
    if not isinstance(value, str) or value not in {str(i) for i in TOPICS}:
        raise ValueError("Tema no válido. Elige uno de la lista o Sin tema.")
    return int(value)


def topic_select(selected=None, *, name="topic_id", include_all=False):
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
