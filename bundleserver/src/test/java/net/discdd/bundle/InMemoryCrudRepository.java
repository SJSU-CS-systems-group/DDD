package net.discdd.bundle;

import org.springframework.data.repository.CrudRepository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

class InMemoryCrudRepository<T, ID> implements CrudRepository<T, ID> {
    protected final Map<ID, T> store = new HashMap<>();
    private final Function<T, ID> idExtractor;

    public InMemoryCrudRepository(Function<T, ID> idExtractor) {
        this.idExtractor = idExtractor;
    }

    @Override
    public <S extends T> S save(S entity) {
        store.put(idExtractor.apply(entity), entity);
        return entity;
    }

    @Override
    public <S extends T> Iterable<S> saveAll(Iterable<S> entities) {
        for (S entity : entities) {
            save(entity);
        }
        return entities;
    }

    @Override
    public Optional<T> findById(ID id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public boolean existsById(ID id) {
        return store.containsKey(id);
    }

    @Override
    public Iterable<T> findAll() {
        return store.values();
    }

    @Override
    public Iterable<T> findAllById(Iterable<ID> ids) {
        List<T> results = new ArrayList<>();
        for (ID id : ids) {
            T entity = store.get(id);
            if (entity != null) {
                results.add(entity);
            }
        }
        return results;
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public void deleteById(ID id) {
        store.remove(id);
    }

    @Override
    public void delete(T entity) {
        store.remove(idExtractor.apply(entity));
    }

    @Override
    public void deleteAllById(Iterable<? extends ID> ids) {
        ids.forEach(this::deleteById);
    }

    @Override
    public void deleteAll(Iterable<? extends T> entities) {
        for (T entity : entities) {
            delete(entity);
        }
    }

    @Override
    public void deleteAll() {
        store.clear();
    }
}
