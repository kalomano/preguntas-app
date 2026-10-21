"""Run locally with SQLite; CI also runs against a disposable PostgreSQL schema.

TEST_DATABASE_URL must refer to a test database, never production.
"""
import io
import json
import os
import sqlite3
import unittest
import uuid
from contextlib import contextmanager
from unittest.mock import patch

with patch.dict(os.environ, {"DATABASE_URL": ""}):
    import app as application
from topics import TOPICS, detect_topic_id, migrate_topics, parse_topic

OPTIONS = [{"letter": c, "text": f"Opción {c}"} for c in "abcd"]


class SQLiteConnection:
    def __init__(self):
        self.raw = sqlite3.connect(":memory:")
        self.raw.row_factory = sqlite3.Row

    def execute(self, sql, params=()):
        return self.raw.execute(sql.replace("%s", "?"), params)


class TopicTests(unittest.TestCase):
    def setUp(self):
        self.url = os.environ.get("TEST_DATABASE_URL")
        if self.url:
            self.c = application.psycopg.connect(self.url, row_factory=application.dict_row)
            self.schema = "test_topics_" + uuid.uuid4().hex
            self.c.execute(f'CREATE SCHEMA "{self.schema}"')
            self.c.execute(f'SET search_path TO "{self.schema}"')
            self.c.commit()
        else:
            self.c = SQLiteConnection()
        self.c.execute("CREATE TABLE users(id INTEGER PRIMARY KEY, name TEXT UNIQUE)")
        self.c.execute("CREATE TABLE questions(id INTEGER PRIMARY KEY, question TEXT, options_json TEXT, correct_index INTEGER, user_id INTEGER NOT NULL, topic_id SMALLINT CHECK(topic_id BETWEEN 1 AND 35), source_url TEXT)")
        self.c.execute("INSERT INTO users VALUES(1, 'Uno'), (2, 'Dos')")
        for qid, uid, topic in [(1, 1, None), (2, 1, 2), (3, 2, 35)]:
            self.c.execute("INSERT INTO questions VALUES(%s,%s,%s,0,%s,%s,NULL)",
                           (qid, f"Pregunta {qid}", json.dumps(OPTIONS), uid, topic))
        # PostgreSQL's production ID is BIGSERIAL; emulate it for route inserts.
        if self.url:
            self.c.execute("CREATE SEQUENCE test_question_ids START 4")
            self.c.execute("ALTER TABLE questions ALTER COLUMN id SET DEFAULT nextval('test_question_ids')")
        @contextmanager
        def db():
            yield self.c
        self.db_patch = patch.object(application, "db", db)
        self.db_patch.start()
        application.app.config.update(TESTING=True, SECRET_KEY="test-only")
        self.client = application.app.test_client()
        with self.client.session_transaction() as session:
            session["user_id"] = 1

    def tearDown(self):
        self.db_patch.stop()
        if self.url:
            self.c.rollback()
            self.c.execute(f'DROP SCHEMA "{self.schema}" CASCADE')
            self.c.commit()
            self.c.close()
        else:
            self.c.raw.close()

    def save(self, **extra):
        return self.client.post("/confirm-save", data={
            "question": "Nueva", "options_json": json.dumps(OPTIONS),
            "correct_index": "0", **extra,
        })

    def row(self, qid):
        return self.c.execute("SELECT * FROM questions WHERE id=%s", (qid,)).fetchone()

    def token(self):
        self.client.get("/questions")
        with self.client.session_transaction() as session:
            return session["topic_csrf"]

    def test_fixed_catalogue_and_input_validation(self):
        self.assertEqual(list(TOPICS), list(range(1, 36)))
        self.assertEqual(TOPICS[1], "Tema 01. FuncionPublica")
        self.assertEqual(TOPICS[35], "Tema 35. Conducción segura en emergencias.pdf")
        for i in TOPICS:
            self.assertEqual(parse_topic(str(i)), i)
        for value in ["0", "36", "-1", "1.0", "abc", "1 OR 1=1", "01"]:
            with self.assertRaises(ValueError):
                parse_topic(value)

    def test_legacy_extension_save_without_topic(self):
        self.assertEqual(self.save().status_code, 302)
        self.assertIsNone(self.row(4)["topic_id"])
        self.assertEqual(self.row(4)["user_id"], 1)
        self.assertEqual(json.loads(self.row(4)["options_json"]), OPTIONS)
        self.assertIsNone(self.row(4)["source_url"])

    def test_auto_detect_bombea_topic_and_store_allowed_origin_link(self):
        self.assertEqual(detect_topic_id("Decreto Legislativo 1/2006, Tº Preliminar"), 4)
        self.save(
            topic_hint="Decreto Legislativo 1/2006, Tº Preliminar",
            source_url="https://bombea.madrid/generador/index.html?return=%2Ftest",
        )
        self.assertEqual(self.row(4)["topic_id"], 4)
        self.assertEqual(self.row(4)["source_url"], "https://bombea.madrid/generador/index.html?return=%2Ftest")

    def test_testazo_topic_detection_and_source_url_validation(self):
        self.assertEqual(detect_topic_id("Tema 13. SVB II.pdf - pregunta"), 13)
        self.save(
            topic_hint="Tema 13. SVB II.pdf",
            source_url="https://app.testazo.com/test/question/123",
        )
        self.assertEqual(self.row(4)["topic_id"], 13)
        self.assertEqual(self.row(4)["source_url"], "https://app.testazo.com/test/question/123")

    def test_untrusted_source_url_is_not_stored(self):
        self.save(source_url="https://evil.example/phishing")
        self.assertIsNone(self.row(4)["source_url"])

    def test_auto_detect_bombea_topic_and_store_source_link(self):
        self.assertEqual(detect_topic_id("Decreto Legislativo 1/2006, Tº Preliminar"), 4)
        self.save(
            topic_hint="Decreto Legislativo 1/2006, Tº Preliminar",
            source_url="https://bombea.madrid/generador/index.html?return=%2Ftest",
        )
        self.assertEqual(self.row(4)["topic_id"], 4)
        self.assertEqual(self.row(4)["source_url"], "https://bombea.madrid/generador/index.html?return=%2Ftest")
        self.assertIn(b"Abrir en Testazo/Bombea", self.client.get("/questions").data)

    def test_testazo_topic_detection_and_source_url_validation(self):
        self.assertEqual(detect_topic_id("Tema 13. SVB II.pdf"), 13)
        self.save(
            topic_hint="Tema 13. SVB II.pdf",
            source_url="https://app.testazo.com/test/question/123",
        )
        self.assertEqual(self.row(4)["topic_id"], 13)
        self.assertEqual(self.row(4)["source_url"], "https://app.testazo.com/test/question/123")

    def test_untrusted_source_url_is_not_stored(self):
        self.save(source_url="https://evil.example/phishing")
        self.assertIsNone(self.row(4)["source_url"])

    def test_invalid_save_does_not_insert(self):
        self.save(topic_id="36")
        self.assertIsNone(self.row(4))

    def test_edit_and_clear_only_topic(self):
        before = dict(self.row(1))
        token = self.token()
        for value, expected in [("15", 15), ("", None)]:
            response = self.client.post("/questions/1/topic", data={"csrf_token": token, "topic_id": value})
            self.assertEqual(response.status_code, 302)
            self.assertEqual(self.row(1)["topic_id"], expected)
        self.assertEqual(dict(self.row(1)), before)

    def test_edit_other_user_or_missing_question_is_denied(self):
        token = self.token()
        before = dict(self.row(3))
        for qid in [3, 999]:
            response = self.client.post(f"/questions/{qid}/topic", data={"csrf_token": token, "topic_id": "1"})
            self.assertEqual(response.status_code, 404)
        self.assertEqual(dict(self.row(3)), before)

    def test_edit_invalid_and_cross_site_requests_are_denied(self):
        self.assertEqual(self.client.post("/questions/1/topic", data={"topic_id": "1"}).status_code, 400)
        self.assertEqual(self.client.post("/questions/1/topic", data={"csrf_token": self.token(), "topic_id": "99"}).status_code, 400)
        self.assertIsNone(self.row(1)["topic_id"])

    def test_anonymous_update_redirects(self):
        with self.client.session_transaction() as session:
            session.clear()
        self.assertEqual(self.client.post("/questions/1/topic").location, "/users")

    def test_all_practice_filters_and_default_resets_filter(self):
        for topic, expected in [("2", [2]), ("none", [1]), ("35", []), ("", [1, 2])]:
            self.client.get("/practice", query_string={"mode": "all", "topic": topic})
            with self.client.session_transaction() as session:
                self.assertEqual(sorted(session["queue"]), expected)
        self.client.get("/practice?mode=all")
        with self.client.session_transaction() as session:
            self.assertEqual(sorted(session["queue"]), [1, 2])

    def test_random_filter_persists_through_answer_and_next(self):
        for topic, qid in [("2", 2), ("none", 1)]:
            self.client.get("/practice", query_string={"topic": topic})
            for _ in range(3):
                response = self.client.get("/next")
                self.assertIn(f"Pregunta {qid}".encode(), response.data)
                self.client.post(f"/answer/{qid}", data={"answer": "0"})
                self.client.post(f"/keep/{qid}")

    def test_invalid_filter_and_empty_selection(self):
        self.assertEqual(self.client.get("/practice?topic=99").location, "/")
        self.client.get("/practice?topic=35")
        self.assertEqual(self.client.get("/next").location, "/")
        self.client.get("/practice?mode=all&topic=35")
        response = self.client.get("/next")
        self.assertIn(b"topic=35", response.data)

    def test_saved_page_stats_and_optional_selectors(self):
        response = self.client.get("/questions")
        self.assertIn(b"Sin tema", response.data)
        self.assertIn(b"Preguntas por tema", response.data)
        self.assertNotIn(b"Pregunta 3", response.data)
        self.assertIn(b"Igualdad.pdf</td><td style='text-align:right'>1", response.data)
        home = self.client.get("/").data
        self.assertIn(b"Todos (2)", home)
        self.assertIn(b"Igualdad.pdf (1)", home)
        self.assertNotIn(b"Conducci", home)

    def test_upload_confirmation_has_optional_default(self):
        with patch.object(application, "parse_capture", return_value=("Captura", OPTIONS, 0)):
            response = self.client.post("/upload", data={"image": (io.BytesIO(b"test"), "test.png")})
        self.assertIn(b"name='topic_id'", response.data)
        self.assertIn(b"value='' selected>Sin tema", response.data)

    @unittest.skipUnless(os.environ.get("TEST_DATABASE_URL"), "PostgreSQL integration runs in CI")
    def test_postgres_migration_twice_preserves_existing_rows_and_constraints(self):
        # Start from the legacy schema in this disposable transaction.
        self.c.execute("ALTER TABLE questions DROP COLUMN topic_id")
        before = [dict(r) for r in self.c.execute("SELECT * FROM questions ORDER BY id").fetchall()]
        migrate_topics(self.c)
        migrate_topics(self.c)
        after = [dict(r) for r in self.c.execute("SELECT * FROM questions ORDER BY id").fetchall()]
        for old, new in zip(before, after):
            self.assertIsNone(new.pop("topic_id"))
            self.assertEqual(old, new)
        for invalid in [0, 36, -1]:
            with self.c.transaction():
                with self.assertRaises(application.psycopg.errors.CheckViolation):
                    with self.c.transaction():
                        self.c.execute("UPDATE questions SET topic_id=%s WHERE id=1", (invalid,))
        self.c.execute("UPDATE questions SET topic_id=35 WHERE id=1")


if __name__ == "__main__":
    unittest.main()
