from typing import List, Optional
from sentence_transformers import SentenceTransformer

_model: Optional[SentenceTransformer] = None


def get_model() -> SentenceTransformer:
    global _model
    if _model is None:
        _model = SentenceTransformer('all-MiniLM-L6-v2')
    return _model


def embed_text(text: str) -> List[float]:
    model = get_model()
    embedding = model.encode(text)
    return embedding.tolist()


def embed_batch(texts: List[str]) -> List[List[float]]:
    model = get_model()
    embeddings = model.encode(texts)
    return embeddings.tolist()