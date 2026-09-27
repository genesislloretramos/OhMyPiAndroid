-- omp guest API — the schema. NOT RUN: this build has no PHP and no MySQL, so this file has never
-- been executed. It is applied on first use, once per request that reaches the database, and every
-- statement is `IF NOT EXISTS` so applying it to a database that already has the tables costs one
-- round trip and changes nothing.
--
-- The columns the code reads and that are NOT here are added by the migration in Db.php, which is
-- why it is not a formality: `conversations.title` and `turns.ended` are load-bearing on a fresh
-- install, and a schema that grew in two places is the kind of thing that works until it does not.

CREATE TABLE IF NOT EXISTS conversations (
  id          INT UNSIGNED    NOT NULL AUTO_INCREMENT,
  name        VARCHAR(191)    NOT NULL,
  created     BIGINT          NOT NULL,
  modified    BIGINT          NOT NULL,
  PRIMARY KEY (id),
  -- 191 and not 255: this is utf8mb4, and an index over a 255-character column is 1020 bytes of
  -- key, which InnoDB will accept on a modern row format and reject on some of them.
  UNIQUE KEY name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per question or answer. `role` is the wire name app.js draws: 'user', 'assistant',
-- 'tool-result'. Anything else is drawn as a system line, which is what a row with a role this
-- build has never heard of should look like.
--
-- There is no `approved` column, and that is the honest shape: the guest's agent is asked about a
-- write by nobody (see Routes::approve), so there is no approval to record. A column that is only
-- ever NULL is a column that says nothing, and the field it would have fed is optional in
-- omp/vm/guestapi/GuestApi.kt for the same reason.
CREATE TABLE IF NOT EXISTS messages (
  id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  conversation_id INT UNSIGNED    NOT NULL,
  at              BIGINT          NOT NULL,
  role            VARCHAR(32)     NOT NULL,
  content         MEDIUMTEXT      NOT NULL,
  tool            VARCHAR(64)     NULL,
  PRIMARY KEY (id),
  -- The reading order of a conversation is (conversation, id), so this is the index the transcript
  -- route wants and the one a primary key on id alone would not serve.
  KEY conversation (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- One row per turn that has started and not yet ended. A turn is what `/api/turns/{id}/cancel`
-- names, and a turn is running exactly while its `ended` is NULL — so the row is the lock, and
-- there is no second mechanism that could disagree with it.
CREATE TABLE IF NOT EXISTS turns (
  id              VARCHAR(64)  NOT NULL,
  conversation_id INT UNSIGNED NOT NULL,
  started         BIGINT       NOT NULL,
  PRIMARY KEY (id),
  KEY conversation (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
