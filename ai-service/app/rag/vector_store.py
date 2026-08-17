from __future__ import annotations

import os
from typing import Dict, List, Optional

from langchain_community.embeddings import HuggingFaceEmbeddings
from langchain_community.vectorstores import Chroma

CHROMA_DIR = os.getenv("CHROMA_DIR", "./chroma_db")

_embeddings: Optional[HuggingFaceEmbeddings] = None
_vectorstore: Optional[Chroma] = None


def get_embeddings() -> HuggingFaceEmbeddings:
    global _embeddings
    if _embeddings is None:
        _embeddings = HuggingFaceEmbeddings(model_name="all-MiniLM-L6-v2")
    return _embeddings


def get_vectorstore() -> Chroma:
    global _vectorstore
    if _vectorstore is None:
        _vectorstore = Chroma(
            persist_directory=CHROMA_DIR,
            embedding_function=get_embeddings(),
            collection_name="fitness_knowledge",
        )
    return _vectorstore


def reset_vectorstore() -> Chroma:
    global _vectorstore
    import shutil

    if os.path.exists(CHROMA_DIR):
        shutil.rmtree(CHROMA_DIR)

    _vectorstore = Chroma(
        persist_directory=CHROMA_DIR,
        embedding_function=get_embeddings(),
        collection_name="fitness_knowledge",
    )
    return _vectorstore


def add_documents(chunks: List[str], metadatas: List[Dict]) -> None:
    store = get_vectorstore()
    ids = []
    for index, metadata in enumerate(metadatas):
        chunk_id = metadata.get("chunk_id") if isinstance(metadata, dict) else None
        ids.append(chunk_id or f"chunk_{index:04d}")
    store.add_texts(texts=chunks, metadatas=metadatas, ids=ids)


def similarity_search(query: str, k: int = 5) -> List[Dict]:
    store = get_vectorstore()
    docs = store.similarity_search(query, k=k)
    results = []
    for doc in docs:
        metadata = dict(doc.metadata or {})
        results.append({
            "content": doc.page_content,
            "metadata": metadata,
            "chunk_id": metadata.get("chunk_id"),
            "source": "dense",
        })
    return results


def get_chunk_count() -> int:
    try:
        store = get_vectorstore()
        collection = store._collection
        return collection.count()
    except Exception:
        return 0
