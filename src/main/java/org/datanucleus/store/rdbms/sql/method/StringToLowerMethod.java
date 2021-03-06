/**********************************************************************
Copyright (c) 2008 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.sql.method;

import java.util.List;

import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.expression.CharacterLiteral;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.sql.expression.StringLiteral;
import org.datanucleus.util.Localiser;

/**
 * Expression handler to invoke the SQL LOWER function.
 * For use in evaluating StringExpression.toLowerCase() where the RDBMS supports this function.
 * Returns a StringExpression "LOWER({stringExpr})".
 */
public class StringToLowerMethod extends SimpleStringMethod
{
    protected String getFunctionName()
    {
        return "LOWER";
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.rdbms.sql.method.SQLMethod#getExpression(org.datanucleus.store.rdbms.sql.expression.SQLExpression, java.util.List)
     */
    public SQLExpression getExpression(SQLStatement stmt, SQLExpression expr, List<SQLExpression> args)
    {
        if (args != null && !args.isEmpty())
        {
            throw new NucleusException(Localiser.msg("060015", "toLowerCase", "StringExpression"));
        }

        if (!expr.isParameter())
        {
            if (expr instanceof StringLiteral)
            {
                String val = (String)((StringLiteral)expr).getValue();
                if (val != null)
                {
                    val = val.toLowerCase();
                }
                return new StringLiteral(stmt, expr.getJavaTypeMapping(), val, null);
            }
            else if (expr instanceof CharacterLiteral)
            {
                String val = (String)((CharacterLiteral)expr).getValue();
                if (val != null)
                {
                    val = val.toLowerCase();
                }
                return new CharacterLiteral(stmt, expr.getJavaTypeMapping(), val, null);
            }
        }

        return super.getExpression(stmt, expr, null);
    }
}