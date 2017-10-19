package sync.pds.solver.nodes;

import wpds.interfaces.State;

public class Node<Stmt, Fact> extends State{

	final protected Stmt stmt;
	final protected Fact variable;
	private int hashCode;

	public Node(Stmt stmt, Fact variable) {
		this.stmt = stmt;
		this.variable = variable;
	}
	
	public Stmt stmt() {
		return stmt;
	}
	
	public Fact fact(){
		return variable;
	}

	@Override
	public int hashCode() {
		if(hashCode != 0)
			return hashCode;
		final int prime = 31;
		int result = 1;
		result = prime * result + ((stmt == null) ? 0 : stmt.hashCode());
		result = prime * result + ((variable == null) ? 0 : variable.hashCode());
		hashCode = result;
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj)
			return true;
		if (obj == null)
			return false;
		if (getClass() != obj.getClass())
			return false;
		Node other = (Node) obj;
		if (stmt == null) {
			if (other.stmt != null)
				return false;
		} else if (!stmt.equals(other.stmt))
			return false;
		if (variable == null) {
			if (other.variable != null)
				return false;
		} else if (!variable.equals(other.variable))
			return false;
		return true;
	}
	
	@Override
	public String toString() {
		return "("+stmt+","+variable+")";
	}

}
