-- Include deleted records so a replay cannot resurrect a removed automatic decision.
CREATE UNIQUE INDEX decisions_chat_message
 ON records ((data->>'userMessageId'))
 WHERE kind='decision' AND data->>'origin'='chat';
