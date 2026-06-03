package de.tudarmstadt.esa.treenail.codegen;

import com.minres.coredsl.coreDsl.NamedEntity;
import com.minres.coredsl.coreDsl.ExpressionInitializer;
import com.minres.coredsl.coreDsl.EntityReference;
import com.minres.coredsl.coreDsl.Expression;
import com.minres.coredsl.coreDsl.AssignmentExpression;
import com.minres.coredsl.coreDsl.PostfixExpression;
import com.minres.coredsl.coreDsl.PrefixExpression;
import com.minres.coredsl.coreDsl.Declaration;
import com.minres.coredsl.coreDsl.DeclarationStatement;
import com.minres.coredsl.coreDsl.Declarator;
import org.eclipse.emf.common.util.TreeIterator;
import org.eclipse.emf.ecore.EObject;
import com.minres.coredsl.coreDsl.ISA;
import com.minres.coredsl.coreDsl.IndexAccessExpression;
import com.minres.coredsl.coreDsl.Statement;
import com.minres.coredsl.coreDsl.TypeQualifier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

class AliasAnalysis {

    // Returns null if any alias initializer could not be resolved
    private static HashSet<NamedEntity> getMutableAliases(NamedEntity entity) {
        var currEntity = entity;
        var confirmedAliases = new HashSet<NamedEntity>();
        // First container is declaration, second declaration statement, third is
        // the statement this is contained in (e.g. CompoundStatement)
        var enclosingScope = currEntity.eContainer().eContainer().eContainer();
        // Alias declarations are only allowed in architectural state, so aliases
        // of local variables are impossible
        if (!(enclosingScope instanceof ISA isa)) {
            if (!(entity.eContainer() instanceof Declaration decl) ||
                    !decl.getQualifiers().contains(TypeQualifier.CONST)) {
                confirmedAliases.add(entity);
            }
            return confirmedAliases;
        }
        // If this declarator is an alias, resolve the initializer until a concrete
        // variable is reached
        while (currEntity instanceof Declarator dtor && dtor.isAlias()) {
            assert dtor.eContainer() instanceof Declaration;
            var decl = (Declaration)dtor.eContainer();
            // A volatile variable may be changed arbitrarily by external entities,
            // even if it is const, so we always have to assume its modified
            if (decl.getQualifiers().contains(TypeQualifier.VOLATILE)) {
                return null;
            }
            if (!decl.getQualifiers().contains(TypeQualifier.CONST)) {
                confirmedAliases.add(currEntity);
            }
            var initializer = dtor.getInitializer();
            if (initializer instanceof ExpressionInitializer exprInit &&
                    exprInit.getValue() instanceof EntityReference entityRef) {
                currEntity = entityRef.getTarget();
            } else {
                // If we can't fully check all aliases, we have to assume that the
                // variable is modified somewhere in the loop
                // TODO: could check more complicated declarations here as well
                return null;
            }
        }
        if (currEntity.eContainer() instanceof Declaration decl) {
            if (decl.getQualifiers().contains(TypeQualifier.VOLATILE)) {
                return null;
            }
            if (decl.getQualifiers().contains(TypeQualifier.CONST)) {
                assert confirmedAliases.isEmpty()
                        : "There can be no non-const aliases for a const variable";
                return confirmedAliases;
            }
        }
        // TODO: this assumes that all entities that do not have a declaration
        //  as parent are mutable. This is the case for BitField, but are there
        //  others?
        confirmedAliases.add(currEntity);
        var aliasDeclarators = new ArrayList<Declarator>();
        for (Statement s : isa.getArchStateBody()) {
            if (s instanceof DeclarationStatement declStmt) {
                var declaration = declStmt.getDeclaration();
                if (declaration.getQualifiers().contains(TypeQualifier.CONST)) {
                    continue;
                }
                for (var dtor : declaration.getDeclarators()) {
                    if (dtor.isAlias()) {
                        aliasDeclarators.add(dtor);
                    }
                }
            }
        }
        // iterate alias declarators to see if any reference any of the other
        // aliases
        for (Declarator aliasDeclarator : aliasDeclarators) {
            if (aliasDeclarator.getInitializer() instanceof
                    ExpressionInitializer exprInit) {
                if (containsOneOf(exprInit.getValue(), confirmedAliases)) {
                    confirmedAliases.add(aliasDeclarator);
                }
            } else {
                assert false : "NYI: ListInitializer";
            }
        }
        return confirmedAliases;
    }

    private static boolean containsOneOf(Expression expr,
                                         HashSet<NamedEntity> entities) {
        while (expr instanceof IndexAccessExpression indexAccess) {
            expr = indexAccess.getTarget();
        }
        assert expr instanceof EntityReference;
        EntityReference entityReference = (EntityReference)expr;
        return entities.contains(entityReference.getTarget());
    }

    // Returns true if we cannot prove that the entity is not modified in the loop
    public static boolean entityMayBeModifiedIn(NamedEntity entity,
                                                List<Statement> statements) {
        var aliases = getMutableAliases(entity);
        if (aliases == null) {
            return true;
        }
        for (var expr : statements) {
            for (TreeIterator<EObject> it = expr.eAllContents(); it.hasNext();) {
                var item = it.next();
                if (item instanceof AssignmentExpression assignmentExpression) {
                    Expression target = assignmentExpression.getTarget();
                    if (containsOneOf(target, aliases)) {
                        return true;
                    }
                } else if (item instanceof PrefixExpression prefix) {
                    // TODO: are there prefix expressions that don't mutate?
                    if (containsOneOf(prefix.getOperand(), aliases)) {
                        return true;
                    }
                } else if (item instanceof PostfixExpression postfix) {
                    // TODO: are there postfix expressions that don't mutate?
                    if (containsOneOf(postfix.getOperand(), aliases)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
