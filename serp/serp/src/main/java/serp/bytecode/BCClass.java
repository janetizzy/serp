package serp.bytecode;

import java.io.*;
import java.net.*;
import java.util.*;

import serp.bytecode.lowlevel.*;
import serp.bytecode.visitor.*;
import serp.util.*;

/**
 * The BCClass represents a class object in the bytecode framework, in many
 * ways mirroring the {@link Class} class of Java reflection. The represented
 * class might be a primitive, array, existing object type, or some new user-
 * defined type. As with most entities in the bytecode framework, the BCClass
 * contains methods to manipulate the low-level state of the class (constant
 * pool indexes, etc), but these can and should be ignored in
 * favor of the available high-level methods.
 *
 * <p>A BCClass instance is loaded from a {@link Project} and remains
 * attached to that project for its lifetime. If a BCClass is removed from
 * its project, the result of any further operations on the class are
 * undefined.</p>
 *
 * <p>Note that if a BCClass represents a primitive or array type, all of the
 * available mutator methods and any methods that access the constant pool
 * will throw {@link UnsupportedOperationException}s.</p>
 *
 * @author Abe White
 */
public class BCClass extends Annotated implements VisitAcceptor {
    private Project _project = null;
    private State _state = null;
    private ClassLoader _loader = null;

    /**
     * Hide constructor. For use by the owning project only.
     */
    BCClass(Project project) {
        _project = project;
    }

    /**
     * Set the class state. For use by the owning project only.
     */
    void setState(State state) {
        _state = state;
    }

    /**
     * Invalidate this class.
     */
    void invalidate() {
        _project = null;
        _state = State.INVALID;
    }

    //////////////////
    // I/O operations
    //////////////////

    /**
     * Initialize from the class definition in the given file. For use by
     * the owning project only.
     */
    void read(File classFile, ClassLoader loader) throws IOException {
        InputStream in = new FileInputStream(classFile);
        try {
            read(in, loader);
        } finally {
            in.close();
        }
    }

    /**
     * Initialize from the class definition in the given stream. For use by
     * the owning project only.
     */
    void read(InputStream instream, ClassLoader loader)
        throws IOException {
        DataInput in = new DataInputStream(instream);

        // header information
        _state.setMagic(in.readInt());
        _state.setMinorVersion(in.readUnsignedShort());
        _state.setMajorVersion(in.readUnsignedShort());

        // constant pool
        _state.getPool().read(in);

        // access flags
        _state.setAccessFlags(in.readUnsignedShort());

        // class, super class, interfaces
        _state.setIndex(in.readUnsignedShort());
        _state.setSuperclassIndex(in.readUnsignedShort());

        List interfaces = _state.getInterfacesHolder();
        interfaces.clear();
        int interfaceCount = in.readUnsignedShort();
        for (int i = 0; i < interfaceCount; i++)
            interfaces.add(Numbers.valueOf(in.readUnsignedShort()));

        // fields
        List fields = _state.getFieldsHolder();
        fields.clear();
        int fieldCount = in.readUnsignedShort();
        BCField field;
        for (int i = 0; i < fieldCount; i++) {
            field = new BCField(this);
            fields.add(field);
            field.read(in);
        }

        // methods
        List methods = _state.getMethodsHolder();
        methods.clear();
        int methodCount = in.readUnsignedShort();
        BCMethod method;
        for (int i = 0; i < methodCount; i++) {
            method = new BCMethod(this);
            methods.add(method);
            method.read(in);
        }

        readAttributes(in);
        _loader = loader;
    }

    /**
     * Initialize from the bytecode of the definition of the given class.
     * For use by the owning project only.
     */
    void read(Class type) throws IOException {
        // find out the length of the package name
        int dotIndex = type.getName().lastIndexOf('.') + 1;

        // strip the package off of the class name
        String className = type.getName().substring(dotIndex);

        // attempt to get the class file for the class as a stream
        InputStream in = type.getResourceAsStream(className + ".class");
        try {
            read(in, type.getClassLoader());
        } finally {
            in.close();
        }
    }

    /**
     * Initialize from the given parsed bytecode.
     * For use by the owning project only.
     */
    void read(BCClass orig) {
        try {
            ByteArrayInputStream in = new ByteArrayInputStream
                (orig.toByteArray());
            read(in, orig.getClassLoader());
            in.close();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe.toString());
        }
    }

    /**
     * Write the class bytecode to the .class file in the proper directory of
     * the CLASSPATH. The file must exist already, so this method only works
     * on existing classes.
     */
    public void write() throws IOException {
        String name = getName();
        int dotIndex = name.lastIndexOf('.') + 1;
        name = name.substring(dotIndex);
        Class type = getType();

        // attempt to get the class file for the class as a stream;
        // we need to use the url decoder in case the target directory
        // has spaces in it
        OutputStream out = new FileOutputStream(URLDecoder.decode
            (type.getResource(name + ".class").getFile()));
        try {
            write(out);
        } finally {
            out.close();
        }
    }

    /**
     * Write the class bytecode to the specified file.
     */
    public void write(File classFile) throws IOException {
        OutputStream out = new FileOutputStream(classFile);
        try {
            write(out);
        } finally {
            out.close();
        }
    }

    /**
     * Write the class bytecode to the specified stream.
     */
    public void write(OutputStream outstream) throws IOException {
        DataOutput out = new DataOutputStream(outstream);

        // header information
        out.writeInt(_state.getMagic());
        out.writeShort(_state.getMinorVersion());
        out.writeShort(_state.getMajorVersion());

        // constant pool
        _state.getPool().write(out);

        // access flags
        out.writeShort(_state.getAccessFlags());

        // class, super class
        out.writeShort(_state.getIndex());
        out.writeShort(_state.getSuperclassIndex());

        // interfaces
        List interfaces = _state.getInterfacesHolder();
        out.writeShort(interfaces.size());
        for (Iterator itr = interfaces.iterator(); itr.hasNext();)
            out.writeShort(((Number) itr.next()).intValue());

        // fields
        List fields = _state.getFieldsHolder();
        out.writeShort(fields.size());
        for (Iterator itr = fields.iterator(); itr.hasNext();)
            ((BCField) itr.next()).write(out);

        // methods
        List methods = _state.getMethodsHolder();
        out.writeShort(methods.size());
        for (Iterator itr = methods.iterator(); itr.hasNext();)
            ((BCMethod) itr.next()).write(out);

        // attributes
        writeAttributes(out);
    }

    /**
     * Return the bytecode of this class as a byte array, possibly for use
     * in a custom {@link ClassLoader}.
     */
    public byte[] toByteArray() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            write(out);
            out.flush();
            return out.toByteArray();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe.toString());
        } finally {
            try { out.close(); } catch (IOException ioe) {}
        }
    }

    /////////////////////
    // Access operations
    /////////////////////

    /**
     * Return the magic number for this class; if this is a valid type, this
     * should be equal to {@link Constants#VALID_MAGIC} (the default value).
     */
    public int getMagic() {
        return _state.getMagic();
    }

    /**
     * Set the magic number for this class; if this is a valid type, this
     * should be equal to {@link Constants#VALID_MAGIC} (the default value).
     */
    public void setMagic(int magic) {
        _state.setMagic(magic);
    }

    /**
     * Return the major version of the bytecode spec used for this class.
     * JVMs are only required to operate with versions that they understand;
     * leaving the default value of {@link Constants#MAJOR_VERSION} is safe.
     */
    public int getMajorVersion() {
        return _state.getMajorVersion();
    }

    /**
     * Set the major version of the bytecode spec used for this class.
     * JVMs are only required to operate with versions that they understand;
     * leaving the default value of {@link Constants#MAJOR_VERSION} is safe.
     */
    public void setMajorVersion(int majorVersion) {
        _state.setMajorVersion(majorVersion);
    }

    /**
     * Get the minor version of the bytecode spec used for this class.
     * JVMs are only required to operate with versions that they understand;
     * leaving the default value of {@link Constants#MINOR_VERSION} is safe.
     */
    public int getMinorVersion() {
        return _state.getMinorVersion();
    }

    /**
     * Set the minor version of the bytecode spec used for this class.
     * JVMs are only required to operate with versions that they understand;
     * leaving the default value of {@link Constants#MINOR_VERSION} is safe.
     */
    public void setMinorVersion(int minorVersion) {
        _state.setMinorVersion(minorVersion);
    }

    /**
     * Return the access flags for this class as a bit array of
     * ACCESS_XXX constants from {@link Constants}. This can be used to
     * transfer access flags between classes without getting/setting each
     * possible flag.
     */
    public int getAccessFlags() {
        return _state.getAccessFlags();
    }

    /**
     * Set the access flags for this class as a bit array of
     * ACCESS_XXX constants from {@link Constants}. This can be used to
     * transfer access flags between classes without getting/setting each
     * possible flag.
     */
    public void setAccessFlags(int access) {
        _state.setAccessFlags(access);
    }

    /**
     * Manipulate the class access flags.
     */
    public boolean isPublic() {
        return (getAccessFlags() & Constants.ACCESS_PUBLIC) > 0;
    }

    /**
     * Manipulate the class access flags.
     */
    public void makePublic() {
        setAccessFlags(getAccessFlags() | Constants.ACCESS_PUBLIC);
    }

    /**
     * Manipulate the class access flags.
     */
    public boolean isPackage() {
        return !isPublic();
    }

    /**
     * Manipulate the class access flags.
     */
    public void makePackage() {
        setAccessFlags(getAccessFlags() & ~Constants.ACCESS_PUBLIC);
    }

    /**
     * Manipulate the class access flags.
     */
    public boolean isFinal() {
        return (getAccessFlags() & Constants.ACCESS_FINAL) > 0;
    }

    /**
     * Manipulate the class access flags.
     */
    public void setFinal(boolean on) {
        if (on) 
            setAccessFlags(getAccessFlags() | Constants.ACCESS_FINAL);
        else
            setAccessFlags(getAccessFlags() & ~Constants.ACCESS_FINAL);
    }

    /**
     * Manipulate the class access flags.
     */
    public boolean isInterface() {
        return (getAccessFlags() & Constants.ACCESS_INTERFACE) > 0;
    }

    /**
     * Manipulate the class access flags.
     */
    public void setInterface(boolean on) {
        if (on) {
            setAccessFlags(getAccessFlags() | Constants.ACCESS_INTERFACE);
            setAbstract(true);
        } else
            setAccessFlags(getAccessFlags() & ~Constants.ACCESS_INTERFACE);
    }

    /**
     * Manipulate the class access flags.
     */
    public boolean isAbstract() {
        return (getAccessFlags() & Constants.ACCESS_ABSTRACT) > 0;
    }

    /**
     * Manipulate the class access flags.
     */
    public void setAbstract(boolean on) {
        if (on)
            setAccessFlags(getAccessFlags() | Constants.ACCESS_ABSTRACT);
        else
            setAccessFlags(getAccessFlags() & ~Constants.ACCESS_ABSTRACT);
    }

    /**
     * Manipulate the class access flags.
     */
    public boolean isSynthetic() {
        return (getAccessFlags() & Constants.ACCESS_SYNTHETIC) > 0;
    }

    /**
     * Manipulate the class access flags.
     */
    public void setSynthetic(boolean on) {
        if (on)
            setAccessFlags(getAccessFlags() | Constants.ACCESS_SYNTHETIC);
        else
            setAccessFlags(getAccessFlags() & ~Constants.ACCESS_SYNTHETIC);
    }

    /**
     * Manipulate the class access flags.
     */
    public boolean isAnnotation() {
        return (getAccessFlags() & Constants.ACCESS_ANNOTATION) > 0;
    }

    /**
     * Manipulate the class access flags.  Setting to true also makes this
     * an interface.
     */
    public void setAnnotation(boolean on) {
        if (on) {
            setAccessFlags(getAccessFlags() | Constants.ACCESS_ANNOTATION);
            setAccessFlags(getAccessFlags() | Constants.ACCESS_INTERFACE);
        } else
            setAccessFlags(getAccessFlags() & ~Constants.ACCESS_ANNOTATION);
    }

    /**
     * Manipulate the class access flags.
     */
    public boolean isEnum() {
        return (getAccessFlags() & Constants.ACCESS_ENUM) > 0;
    }

    /**
     * Manipulate the class access flags.
     */
    public void setEnum(boolean on) {
        if (on)
            setAccessFlags(getAccessFlags() | Constants.ACCESS_ENUM);
        else
            setAccessFlags(getAccessFlags() & ~Constants.ACCESS_ENUM);
    }

    /**
     * Return true if this class is a primitive type.
     */
    public boolean isPrimitive() {
        return _state.isPrimitive();
    }

    /**
     * Return true if this class is an array type.
     */
    public boolean isArray() {
        return _state.isArray();
    }

    /////////////////////////
    // Class name operations
    /////////////////////////

    /**
     * Return the {@link ConstantPool} index of the
     * {@link ClassEntry} for this class. Returns 0 if the class does not
     * have a constant pool (such as a primitive or array).
     */
    public int getIndex() {
        return _state.getIndex();
    }

    /**
     * Set the {@link ConstantPool} index of the {@link ClassEntry} for this
     * class. Unlike most other low-level methods, the index
     * will be checked against the pool immediately;
     * classes must have a valid name at all times.
     */
    public void setIndex(int index) {
        String oldName = getName();
        String newName = ((ClassEntry) getPool().getEntry(index)).
            getNameEntry().getValue();
        beforeRename(oldName, newName);
        _state.setIndex(index);
    }

    /**
     * Return the name of this class, including package name. The name will
     * be in a form suitable for a {@link Class#forName} call.
     */
    public String getName() {
        return _state.getName();
    }

    /**
     * Return the name of the class only, without package.
     */
    public String getClassName() {
        String name = _project.getNameCache().getExternalForm(getName(), true);
        return name.substring(name.lastIndexOf('.') + 1);
    }

    /**
     * Return the package name only, without class, or null if none.
     */
    public String getPackageName() {
        String name = _project.getNameCache().getExternalForm(getName(), true);
        int index = name.lastIndexOf('.');
        if (index == -1)
            return null;
        return name.substring(0, index);
    }

    /**
     * Set the name of this class, including package name.
     */
    public void setName(String name) {
        name = _project.getNameCache().getExternalForm(name, false);
        String oldName = getName();

        // get a reference to the class entry for this class
        int index = getIndex();
        if (index == 0)
            index = getPool().findClassEntry(name, true);
        ClassEntry entry = (ClassEntry) getPool().getEntry(index);

        // make sure the rename is ok with the project
        beforeRename(oldName, name);

        // reset the name index of the class entry to the new name
        int nameIndex = getPool().findUTF8Entry(_project.getNameCache().
            getInternalForm(name, false), true);
        entry.setNameIndex(nameIndex);

        // we might have just added a new entry; set the index
        _state.setIndex(index);
    }

    /**
     * Return the {@link Class} object for this class, if it is loadable.
     */
    public Class getType() {
        return Strings.toClass(getName(), getClassLoader());
    }

    /**
     * Return the component type name of this class, or null if not an array.
     * The name will be in a form suitable for a {@link Class#forName} call.
     */
    public String getComponentName() {
        return _state.getComponentName();
    }

    /**
     * Return the component type of this class, or null if not an array.
     */
    public Class getComponentType() {
        String componentName = getComponentName();
        if (componentName == null)
            return null;
        return Strings.toClass(componentName, getClassLoader());
    }

    /**
     * Return the component type of this class, or null if not an array.
     */
    public BCClass getComponentBC() {
        String componentName = getComponentName();
        if (componentName == null)
            return null;
        return getProject().loadClass(componentName, getClassLoader());
    }

    /////////////////////////
    // Superclass operations
    /////////////////////////

    /**
     * Return the {@link ConstantPool} index of the
     * {@link ClassEntry} for the superclass of this class. Returns -1 if
     * the class does not have a constant pool (such as a primitive or array).
     */
    public int getSuperclassIndex() {
        return _state.getSuperclassIndex();
    }

    /**
     * Set the {@link ConstantPool} index of the
     * {@link ClassEntry} for the superclass of this class.
     */
    public void setSuperclassIndex(int index) {
        _state.setSuperclassIndex(index);
    }

    /**
     * Return the name of the superclass for this class, including package
     * name. The name will be in a form suitable for a
     * {@link Class#forName} call, or null for types without superclasses.
     */
    public String getSuperclassName() {
        return _state.getSuperclassName();
    }

    /**
     * Return the {@link Class} object for the superclass of this class, if it
     * is loadable. Returns null for types without superclasses.
     */
    public Class getSuperclassType() {
        String name = getSuperclassName();
        if (name == null)
            return null;
        return Strings.toClass(name, getClassLoader());
    }

    /**
     * Return the bytecode of the superclass of this class, or
     * null for types without superclasses.
     */
    public BCClass getSuperclassBC() {
        String name = getSuperclassName();
        if (name == null)
            return null;
        return getProject().loadClass(name, getClassLoader());
    }

    /**
     * Set the superclass of this class.
     */
    public void setSuperclass(String name) {
        if (name == null)
            setSuperclassIndex(0);
        else
            setSuperclassIndex(getPool().findClassEntry(_project.getNameCache().
                getInternalForm(name, false), true));
    }

    /**
     * Set the superclass of this class.
     */
    public void setSuperclass(Class type) {
        if (type == null)
            setSuperclass((String) null);
        else
            setSuperclass(type.getName());
    }

    /**
     * Set the superclass of this class.
     */
    public void setSuperclass(BCClass type) {
        if (type == null)
            setSuperclass((String) null);
        else
            setSuperclass(type.getName());
    }

    ////////////////////////
    // Interface operations
    ////////////////////////

    /**
     * Return the list of {@link ConstantPool} indexes of the
     * {@link ClassEntry}s describing all the interfaces this class declares
     * that it implements/extends.
     *
     * @return the implmented interfaces, or an empty array if none
     */
    public int[] getDeclaredInterfaceIndexes() {
        List interfaces = _state.getInterfacesHolder();
        int[] indexes = new int[interfaces.size()];
        for (int i = 0; i < interfaces.size(); i++)
            indexes[i] = ((Number) interfaces.get(i)).intValue();
        return indexes;
    }

    /**
     * Set the list of {@link ConstantPool} indexes of the
     * {@link ClassEntry}s describing all the interfaces this class declares
     * it implements/extends; set to null or an empty array if none.
     */
    public void setDeclaredInterfaceIndexes(int[] interfaceIndexes) {
        List stateIndexes = _state.getInterfacesHolder();
        stateIndexes.clear();
        Integer idx;
        for (int i = 0; i < interfaceIndexes.length; i++) {
            idx = Numbers.valueOf(interfaceIndexes[i]);
            if (!stateIndexes.contains(idx))
                stateIndexes.add(idx);
        }
    }

    /**
     * Return the names of the interfaces declared for this class, including
     * package names, or an empty array if none. The names will be in a form
     * suitable for a {@link Class#forName} call.
     */
    public String[] getDeclaredInterfaceNames() {
        int[] indexes = getDeclaredInterfaceIndexes();
        String[] names = new String[indexes.length];
        ClassEntry entry;
        for (int i = 0; i < indexes.length; i++) {
            entry = (ClassEntry) getPool().getEntry(indexes[i]);
            names[i] = _project.getNameCache().getExternalForm
                (entry.getNameEntry().getValue(), false);
        }
        return names;
    }

    /**
     * Return the {@link Class} objects for the declared interfaces of this
     * class, or an empty array if none.
     */
    public Class[] getDeclaredInterfaceTypes() {
        String[] names = getDeclaredInterfaceNames();
        Class[] types = new Class[names.length];
        for (int i = 0; i < names.length; i++)
            types[i] = Strings.toClass(names[i], getClassLoader());
        return types;
    }

    /**
     * Return the bytecode for the declared interfaces of this class, or an
     * empty array if none.
     */
    public BCClass[] getDeclaredInterfaceBCs() {
        String[] names = getDeclaredInterfaceNames();
        BCClass[] types = new BCClass[names.length];
        for (int i = 0; i < names.length; i++)
            types[i] = getProject().loadClass(names[i], getClassLoader());
        return types;
    }

    /**
     * Set the interfaces declared implemented/extended by this class; set to
     * null or an empty array if none.
     */
    public void setDeclaredInterfaces(String[] interfaces) {
        clearDeclaredInterfaces();
        if (interfaces != null)
            for (int i = 0; i < interfaces.length; i++)
                declareInterface(interfaces[i]);
    }

    /**
     * Set the interfaces declared implemented/extended by this class; set to
     * null or an empty array if none.
     */
    public void setDeclaredInterfaces(Class[] interfaces) {
        String[] names = null;
        if (interfaces != null) {
            names = new String[interfaces.length];
            for (int i = 0; i < interfaces.length; i++)
                names[i] = interfaces[i].getName();
        }
        setDeclaredInterfaces(names);
    }

    /**
     * Set the interfaces declared implemented/extended by this class; set to
     * null or an empty array if none.
     */
    public void setDeclaredInterfaces(BCClass[] interfaces) {
        String[] names = null;
        if (interfaces != null) {
            names = new String[interfaces.length];
            for (int i = 0; i < interfaces.length; i++)
                names[i] = interfaces[i].getName();
        }
        setDeclaredInterfaces(names);
    }

    /**
     * Return the names of all unique interfaces implemented by this class,
     * including those of all superclasses. The names will be returned in a
     * form suitable for a {@link Class#forName} call.
     * This method does not recurse into interfaces-of-interfaces.
     */
    public String[] getInterfaceNames() {
        Collection allNames = new LinkedList();
        String[] names;
        for (BCClass type = this; type != null; type = type.getSuperclassBC()) {
            names = type.getDeclaredInterfaceNames();
            for (int i = 0; i < names.length; i++)
                allNames.add(names[i]);
        }
        return (String[]) allNames.toArray(new String[allNames.size()]);
    }

    /**
     * Return the {@link Class} objects of all unique interfaces implemented
     * by this class, including those of all superclasses.
     * This method does not recurse into interfaces-of-interfaces.
     */
    public Class[] getInterfaceTypes() {
        Collection allTypes = new LinkedList();
        Class[] types;
        for (BCClass type = this; type != null; type = type.getSuperclassBC()) {
            types = type.getDeclaredInterfaceTypes();
            for (int i = 0; i < types.length; i++)
                allTypes.add(types[i]);
        }
        return (Class[]) allTypes.toArray(new Class[allTypes.size()]);
    }

    /**
     * Return the bytecode of all unique interfaces implemented by this class,
     * including those of all superclasses.
     * This method does not recurse into interfaces-of-interfaces.
     */
    public BCClass[] getInterfaceBCs() {
        Collection allTypes = new LinkedList();
        BCClass[] types;
        for (BCClass type = this; type != null; type = type.getSuperclassBC()) {
            types = type.getDeclaredInterfaceBCs();
            for (int i = 0; i < types.length; i++)
                allTypes.add(types[i]);
        }
        return (BCClass[]) allTypes.toArray(new BCClass[allTypes.size()]);
    }

    /**
     * Clear this class of all interface declarations.
     */
    public void clearDeclaredInterfaces() {
        _state.getInterfacesHolder().clear();
    }

    /**
     * Remove an interface declared by this class.
     *
     * @return true if the class had the interface, false otherwise
     */
    public boolean removeDeclaredInterface(String name) {
        String[] names = getDeclaredInterfaceNames();
        Iterator itr = _state.getInterfacesHolder().iterator();
        for (int i = 0; i < names.length; i++) {
            itr.next();
            if (names[i].equals(name)) {
                itr.remove();
                return true;
            }
        }
        return false;
    }

    /**
     * Remove an interface declared by this class.
     *
     * @return true if the class had the interface, false otherwise
     */
    public boolean removeDeclaredInterface(Class type) {
        if (type == null)
            return false;
        return removeDeclaredInterface(type.getName());
    }

    /**
     * Remove an interface declared by this class.
     *
     * @return true if the class had the interface, false otherwise
     */
    public boolean removeDeclaredInterface(BCClass type) {
        if (type == null)
            return false;
        return removeDeclaredInterface(type.getName());
    }

    /**
     * Rearrange declared interface order.  
     */
    public void moveDeclaredInterface(int fromIdx, int toIdx) {
        if (fromIdx == toIdx)
            return;
        List interfaces = _state.getInterfacesHolder();
        Object o = interfaces.remove(fromIdx);
        interfaces.add(toIdx, o);
    }

    /**
     * Add an interface to those declared by this class.
     */
    public void declareInterface(String name) {
        Integer index = Numbers.valueOf(getPool().findClassEntry(_project.
            getNameCache().getInternalForm(name, false), true));
        List interfaces = _state.getInterfacesHolder();
        if (!interfaces.contains(index))
            interfaces.add(index);
    }

    /**
     * Add an interface to those declared by this class.
     */
    public void declareInterface(Class type) {
        declareInterface(type.getName());
    }

    /**
     * Add an interface to those declared by this class.
     */
    public void declareInterface(BCClass type) {
        declareInterface(type.getName());
    }

    /**
     * Return true if this class or any of its superclasses implement/extend
     * the given interface/class.
     * This method does not recurse into interfaces-of-interfaces.
     */
    public boolean isInstanceOf(String name) {
        name = _project.getNameCache().getExternalForm(name, false);
        String[] interfaces = getInterfaceNames();
        for (int i = 0; i < interfaces.length; i++)
            if (interfaces[i].equals(name))
                return true;
        for (BCClass type = this; type != null; type = type.getSuperclassBC())
            if (type.getName().equals(name))
                return true;
        return false;
    }

    /**
     * Return true if this class or any of its superclasses implement/extend
     * the given interface/class.
     * This method does not recurse into interfaces-of-interfaces.
     */
    public boolean isInstanceOf(Class type) {
        if (type == null)
            return false;
        return isInstanceOf(type.getName());
    }

    /**
     * Return true if this class or any of its superclasses implement/extend
     * the given interface/class.
     * This method does not recurse into interfaces-of-interfaces.
     */
    public boolean isInstanceOf(BCClass type) {
        if (type == null)
            return false;
        return isInstanceOf(type.getName());
    }

    //////////////////////
    // Field operations
    //////////////////////

    /**
     * Return all the declared fields of this class, or an empty array if none.
     */
    public BCField[] getDeclaredFields() {
        List fields = _state.getFieldsHolder();
        return (BCField[]) fields.toArray(new BCField[fields.size()]);
    }

    /**
     * Return the declared field with the given name, or null if none.
     */
    public BCField getDeclaredField(String name) {
        BCField[] fields = getDeclaredFields();
        for (int i = 0; i < fields.length; i++)
            if (fields[i].getName().equals(name))
                return fields[i];
        return null;
    }

    /**
     * Return all the fields of this class, including those of all
     * superclasses, or an empty array if none.
     */
    public BCField[] getFields() {
        Collection allFields = new LinkedList();
        BCField[] fields;
        for (BCClass type = this; type != null; type = type.getSuperclassBC()) {
            fields = type.getDeclaredFields();
            for (int i = 0; i < fields.length; i++)
                allFields.add(fields[i]);
        }
        return (BCField[]) allFields.toArray(new BCField[allFields.size()]);
    }

    /**
     * Return all fields with the given name, including those of all
     * superclasses, or an empty array if none.
     */
    public BCField[] getFields(String name) {
        List matches = new LinkedList();
        BCField[] fields = getFields();
        for (int i = 0; i < fields.length; i++)
            if (fields[i].getName().equals(name))
                matches.add(fields[i]);
        return (BCField[]) matches.toArray(new BCField[matches.size()]);
    }

    /**
     * Set the fields for this class; this method is useful for importing all
     * fields from another class. Set to null or empty array if none.
     */
    public void setDeclaredFields(BCField[] fields) {
        clearDeclaredFields();
        if (fields != null)
            for (int i = 0; i < fields.length; i++)
                declareField(fields[i]);
    }

    /**
     * Import the information from given field as a new field in this class.
     *
     * @return the added field
     */
    public BCField declareField(BCField field) {
        BCField newField = declareField(field.getName(), field.getTypeName());
        newField.setAccessFlags(field.getAccessFlags());
        newField.setAttributes(field.getAttributes());
        return newField;
    }

    /**
     * Add a field to this class.
     *
     * @return the added field
     */
    public BCField declareField(String name, String type) {
        BCField field = new BCField(this);
        _state.getFieldsHolder().add(field);
        field.initialize(name, _project.getNameCache().getInternalForm(type, 
            true));
        return field;
    }

    /**
     * Add a field to this class.
     *
     * @return the added field
     */
    public BCField declareField(String name, Class type) {
        String typeName = (type == null) ? null : type.getName();
        return declareField(name, typeName);
    }

    /**
     * Add a field to this class.
     *
     * @return the added field
     */
    public BCField declareField(String name, BCClass type) {
        String typeName = (type == null) ? null : type.getName();
        return declareField(name, typeName);
    }

    /**
     * Clear all fields from this class.
     */
    public void clearDeclaredFields() {
        List fields = _state.getFieldsHolder();
        BCField field;
        for (Iterator itr = fields.iterator(); itr.hasNext();) {
            field = (BCField) itr.next();
            itr.remove();
            field.invalidate();
        }
    }

    /**
     * Remove a field from this class. After this method, the removed field
     * will be invalid, and the result of any operations on it is undefined.
     *
     * @return true if this class contained the field, false otherwise
     */
    public boolean removeDeclaredField(String name) {
        List fields = _state.getFieldsHolder();
        BCField field;
        for (Iterator itr = fields.iterator(); itr.hasNext();) {
            field = (BCField) itr.next();
            if (field.getName().equals(name)) {
                itr.remove();
                field.invalidate();
                return true;
            }
        }
        return false;
    }

    /**
     * Remove a field from this class. After this method, the removed field
     * will be invalid, and the result of any operations on it is undefined.
     *
     * @return true if this class contained the field, false otherwise
     */
    public boolean removeDeclaredField(BCField field) {
        if (field == null)
            return false;
        return removeDeclaredField(field.getName());
    }

    /**
     * Rearrange declared field order.  
     */
    public void moveDeclaredField(int fromIdx, int toIdx) {
        if (fromIdx == toIdx)
            return;
        List fields = _state.getFieldsHolder();
        Object o = fields.remove(fromIdx);
        fields.add(toIdx, o);
    }

    //////////////////////
    // Method operations
    //////////////////////

    /**
     * Return all methods declared by this class. Constructors and static
     * initializers are included.
     */
    public BCMethod[] getDeclaredMethods() {
        List methods = _state.getMethodsHolder();
        return (BCMethod[]) methods.toArray(new BCMethod[methods.size()]);
    }

    /**
     * Return the declared method with the given name, or null if none.
     * If multiple methods are declared with the given name, which is returned
     * is undefined.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod getDeclaredMethod(String name) {
        BCMethod[] methods = getDeclaredMethods();
        for (int i = 0; i < methods.length; i++)
            if (methods[i].getName().equals(name))
                return methods[i];
        return null;
    }

    /**
     * Return all the declared methods with the given name, or an empty array
     * if none.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod[] getDeclaredMethods(String name) {
        Collection matches = new LinkedList();
        BCMethod[] methods = getDeclaredMethods();
        for (int i = 0; i < methods.length; i++)
            if (methods[i].getName().equals(name))
                matches.add(methods[i]);
        return (BCMethod[]) matches.toArray(new BCMethod[matches.size()]);
    }

    /**
     * Return the declared method with the given name and parameter types,
     * or null if none. If multiple methods are declared with the given name
     * and parameters, which is returned is undefined.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod getDeclaredMethod(String name, String[] paramTypes) {
        if (paramTypes == null)
            paramTypes = new String[0];

        BCMethod[] methods = getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name) 
                && paramsMatch(methods[i], paramTypes))
                return methods[i];
        }
        return null;
    }

    /**
     * Return true iff the given method's parameters match <code>params</code>.
     */
    private boolean paramsMatch(BCMethod meth, String[] params) {
        String[] mparams = meth.getParamNames();
        if (mparams.length != params.length)
            return false;

        for (int i = 0; i < params.length; i++) {
            if (!mparams[i].equals(_project.getNameCache().
                getExternalForm(params[i], false)))
                return false;
        }
        return true;
    }

    /**
     * Return the declared method with the given name and parameter types,
     * or null if none. If multiple methods are declared with the given name
     * and parameters, which is returned is undefined.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod getDeclaredMethod(String name, Class[] paramTypes) {
        if (paramTypes == null)
            return getDeclaredMethod(name, (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return getDeclaredMethod(name, paramNames);
    }

    /**
     * Return the declared method with the given name and parameter types,
     * or null if none. If multiple methods are declared with the given name
     * and parameters, which is returned is undefined.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod getDeclaredMethod(String name, BCClass[] paramTypes) {
        if (paramTypes == null)
            return getDeclaredMethod(name, (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return getDeclaredMethod(name, paramNames);
    }

    /**
     * Return all declared methods with the given name and parameter types.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod[] getDeclaredMethods(String name, String[] paramTypes) {
        if (paramTypes == null)
            paramTypes = new String[0];

        BCMethod[] methods = getDeclaredMethods();
        List matches = null;
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name) 
                && paramsMatch(methods[i], paramTypes)) {
                if (matches == null)
                    matches = new ArrayList(3);
                matches.add(methods[i]);
            }
        }
        if (matches == null)
            return new BCMethod[0];
        return (BCMethod[]) matches.toArray(new BCMethod[matches.size()]);
    }

    /**
     * Return all declared methods with the given name and parameter types.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod[] getDeclaredMethods(String name, Class[] paramTypes) {
        if (paramTypes == null)
            return getDeclaredMethods(name, (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return getDeclaredMethods(name, paramNames);
    }

    /**
     * Return all declared methods with the given name and parameter types.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod[] getDeclaredMethods(String name, BCClass[] paramTypes) {
        if (paramTypes == null)
            return getDeclaredMethods(name, (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return getDeclaredMethods(name, paramNames);
    }

    /**
     * Return the declared method with the given name and signature,
     * or null if none.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod getDeclaredMethod(String name, String returnType, 
        String[] paramTypes) {
        if (paramTypes == null)
            paramTypes = new String[0];

        BCMethod[] methods = getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            if (methods[i].getName().equals(name) 
                && methods[i].getReturnName().equals(_project.getNameCache().
                    getExternalForm(returnType, false))
                && paramsMatch(methods[i], paramTypes))
                return methods[i];
        }
        return null;
    }

    /**
     * Return the declared method with the given name and signature,
     * or null if none.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod getDeclaredMethod(String name, Class returnType, 
        Class[] paramTypes) {
        if (paramTypes == null)
            return getDeclaredMethod(name, returnType.getName(), 
                (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return getDeclaredMethod(name, returnType.getName(), paramNames);
    }

    /**
     * Return the declared method with the given name and signature,
     * or null if none.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod getDeclaredMethod(String name, BCClass returnType, 
        BCClass[] paramTypes) {
        if (paramTypes == null)
            return getDeclaredMethod(name, returnType.getName(), 
                (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return getDeclaredMethod(name, returnType.getName(), paramNames);
    }

    /**
     * Return the methods of this class, including those of all superclasses,
     * or an empty array if none.
     * The base version of methods that are overridden will be included, as
     * will all constructors and static initializers.
     * The methods will be ordered from those in the most-specific type up to
     * those in {@link Object}.
     */
    public BCMethod[] getMethods() {
        Collection allMethods = new LinkedList();
        BCMethod[] methods;
        for (BCClass type = this; type != null; type = type.getSuperclassBC()) {
            methods = type.getDeclaredMethods();
            for (int i = 0; i < methods.length; i++)
                allMethods.add(methods[i]);
        }
        return (BCMethod[]) allMethods.toArray(new BCMethod[allMethods.size()]);
    }

    /**
     * Return the methods with the given name, including those of all
     * superclasses, or an empty array if none.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod[] getMethods(String name) {
        Collection matches = new LinkedList();
        BCMethod[] methods = getMethods();
        for (int i = 0; i < methods.length; i++)
            if (methods[i].getName().equals(name))
                matches.add(methods[i]);
        return (BCMethod[]) matches.toArray(new BCMethod[matches.size()]);
    }

    /**
     * Return the methods with the given name and parameter types, including
     * those of all superclasses, or an empty array if none.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod[] getMethods(String name, String[] paramTypes) {
        if (paramTypes == null)
            paramTypes = new String[0];

        String[] curParams;
        boolean match;
        BCMethod[] methods = getMethods();
        Collection matches = new LinkedList();
        for (int i = 0; i < methods.length; i++) {
            if (!methods[i].getName().equals(name))
                continue;
            curParams = methods[i].getParamNames();
            if (curParams.length != paramTypes.length)
                continue;

            match = true;
            for (int j = 0; j < paramTypes.length; j++) {
                if (!curParams[j].equals(_project.getNameCache().
                    getExternalForm(paramTypes[j], false))) {
                    match = false;
                    break;
                }
            }
            if (match)
                matches.add(methods[i]);
        }
        return (BCMethod[]) matches.toArray(new BCMethod[matches.size()]);
    }

    /**
     * Return the methods with the given name and parameter types, including
     * those of all superclasses, or an empty array if none.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod[] getMethods(String name, Class[] paramTypes) {
        if (paramTypes == null)
            return getMethods(name, (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return getMethods(name, paramNames);
    }

    /**
     * Return the methods with the given name and parameter types, including
     * those of all superclasses, or an empty array if none.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     */
    public BCMethod[] getMethods(String name, BCClass[] paramTypes) {
        if (paramTypes == null)
            return getMethods(name, (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return getMethods(name, paramNames);
    }

    /**
     * Set the methods for this class; this method is useful for importing all
     * methods from another class. Set to null or empty array if none.
     */
    public void setDeclaredMethods(BCMethod[] methods) {
        clearDeclaredMethods();
        if (methods != null)
            for (int i = 0; i < methods.length; i++)
                declareMethod(methods[i]);
    }

    /**
     * Import the information in the given method as a new method of this class.
     *
     * @return the added method
     */
    public BCMethod declareMethod(BCMethod method) {
        BCMethod newMethod = declareMethod(method.getName(), 
            method.getReturnName(), method.getParamNames());
        newMethod.setAccessFlags(method.getAccessFlags());
        newMethod.setAttributes(method.getAttributes());
        return newMethod;
    }

    /**
     * Add a method to this class.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     *
     * @return the added method
     */
    public BCMethod declareMethod(String name, String returnType,
        String[] paramTypes) {
        BCMethod method = new BCMethod(this);
        _state.getMethodsHolder().add(method);
        method.initialize(name, _project.getNameCache().
            getDescriptor(returnType, paramTypes));
        return method;
    }

    /**
     * Add a method to this class.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     *
     * @return the added method
     */
    public BCMethod declareMethod(String name, Class returnType,
        Class[] paramTypes) {
        String[] paramNames = null;
        if (paramTypes != null) {
            paramNames = new String[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++)
                paramNames[i] = paramTypes[i].getName();
        }
        String returnName = (returnType == null) ? null : returnType.getName();
        return declareMethod(name, returnName, paramNames);
    }

    /**
     * Add a method to this class.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     *
     * @return the added method
     */
    public BCMethod declareMethod(String name, BCClass returnType,
        BCClass[] paramTypes) {
        String[] paramNames = null;
        if (paramTypes != null) {
            paramNames = new String[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++)
                paramNames[i] = paramTypes[i].getName();
        }
        String returnName = (returnType == null) ? null : returnType.getName();
        return declareMethod(name, returnName, paramNames);
    }

    /**
     * Clear all declared methods from this class.
     */
    public void clearDeclaredMethods() {
        List methods = _state.getMethodsHolder();
        BCMethod method;
        for (Iterator itr = methods.iterator(); itr.hasNext();) {
            method = (BCMethod) itr.next();
            itr.remove();
            method.invalidate();
        }
    }

    /**
     * Remove a method from this class. After this method, the removed method
     * will be invalid, and the result of any operations on it is undefined.
     * If multiple methods match the given name, which is removed is undefined.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     *
     * @return true if this class contained the method, false otherwise
     */
    public boolean removeDeclaredMethod(String name) {
        List methods = _state.getMethodsHolder();
        BCMethod method;
        for (Iterator itr = methods.iterator(); itr.hasNext();) {
            method = (BCMethod) itr.next();
            if (method.getName().equals(name)) {
                itr.remove();
                method.invalidate();
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a method from this class. After this method, the removed method
     * will be invalid, and the result of any operations on it is undefined.
     *
     * @return true if this class contained the method, false otherwise
     */
    public boolean removeDeclaredMethod(BCMethod method) {
        if (method == null)
            return false;
        return removeDeclaredMethod(method.getName(), method.getParamNames());
    }

    /**
     * Removes a method from this class. After this method, the removed method
     * will be invalid, and the result of any operations on it is undefined.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     *
     * @return true if this class contained the method, false otherwise
     */
    public boolean removeDeclaredMethod(String name, String[] paramTypes) {
        if (paramTypes == null)
            paramTypes = new String[0];

        String[] curParams;
        boolean match;
        List methods = _state.getMethodsHolder();
        BCMethod method;
        for (Iterator itr = methods.iterator(); itr.hasNext();) {
            method = (BCMethod) itr.next();
            if (!method.getName().equals(name))
                continue;
            curParams = method.getParamNames();
            if (curParams.length != paramTypes.length)
                continue;

            match = true;
            for (int j = 0; j < paramTypes.length; j++) {
                if (!curParams[j].equals(_project.getNameCache().
                    getExternalForm(paramTypes[j], false))) {
                    match = false;
                    break;
                }
            }
            if (match) {
                itr.remove();
                method.invalidate();
                return true;
            }
        }
        return false;
    }

    /**
     * Removes a method from this class. After this method, the removed method
     * will be invalid, and the result of any operations on it is undefined.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     *
     * @return true if this class contained the method, false otherwise
     */
    public boolean removeDeclaredMethod(String name, Class[] paramTypes) {
        if (paramTypes == null)
            return removeDeclaredMethod(name, (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return removeDeclaredMethod(name, paramNames);
    }

    /**
     * Removes a method from this class. After this method, the removed method
     * will be invalid, and the result of any operations on it is undefined.
     * Note that in bytecode, constructors are named <code>&lt;init&gt;</code>
     * and static initializers are named <code>&lt;clinit&gt;</code>.
     *
     * @return true if this class contained the method, false otherwise
     */
    public boolean removeDeclaredMethod(String name, BCClass[] paramTypes) {
        if (paramTypes == null)
            return removeDeclaredMethod(name, (String[]) null);

        String[] paramNames = new String[paramTypes.length];
        for (int i = 0; i < paramTypes.length; i++)
            paramNames[i] = paramTypes[i].getName();
        return removeDeclaredMethod(name, paramNames);
    }

    /**
     * Rearrange method order.  
     */
    public void moveDeclaredMethod(int fromIdx, int toIdx) {
        if (fromIdx == toIdx)
            return;
        List methods = _state.getMethodsHolder();
        Object o = methods.remove(fromIdx);
        methods.add(toIdx, o);
    }

    ///////////////////////
    // Convenience methods
    ///////////////////////

    /**
     * Convenience method to add a default constructor to this class.
     * If a default constructor already exists, this method will return it
     * without modification.
     * This method can only be called if the superclass has been set.
     *
     * @return the default constructor
     */
    public BCMethod addDefaultConstructor() {
        BCMethod method = getDeclaredMethod("<init>", (String[]) null);
        if (method != null)
            return method;

        method = declareMethod("<init>", void.class, null);
        Code code = method.getCode(true);
        code.setMaxStack(1);
        code.setMaxLocals(1);

        code.xload().setThis();
        code.invokespecial()
            .setMethod(getSuperclassName(), "<init>", "void", null);
        code.vreturn();
        return method;
    }

    /**
     * Return source file information for the class.
     * Acts internally through the {@link Attributes} interface.
     *
     * @param add if true, a new source file attribute will be added
     * if not already present
     * @return the source file information, or null if none and the
     * <code>add</code> param is set to false
     */
    public SourceFile getSourceFile(boolean add) {
        SourceFile source = (SourceFile) getAttribute(Constants.ATTR_SOURCE);
        if (!add || (source != null))
            return source;
        return (SourceFile) addAttribute(Constants.ATTR_SOURCE);
    }

    /**
     * Remove the source file attribute for the class.
     * Acts internally through the {@link Attributes} interface.
     *
     * @return true if there was a file to remove
     */
    public boolean removeSourceFile() {
        return removeAttribute(Constants.ATTR_SOURCE);
    }

    /**
     * Return inner classes information for the class.
     * Acts internally through the {@link Attributes} interface.
     *
     * @param add if true, a new inner classes attribute will be added
     * if not already present
     * @return the inner classes information, or null if none and the
     * <code>add</code> param is set to false
     */
    public InnerClasses getInnerClasses(boolean add) {
        InnerClasses inner = (InnerClasses) getAttribute
            (Constants.ATTR_INNERCLASS);
        if (!add || (inner != null))
            return inner;
        return (InnerClasses) addAttribute(Constants.ATTR_INNERCLASS);
    }

    /**
     * Remove the inner classes attribute for the class.
     * Acts internally through the {@link Attributes} interface.
     *
     * @return true if there was an attribute to remove
     */
    public boolean removeInnerClasses() {
        return removeAttribute(Constants.ATTR_INNERCLASS);
    }

    /**
     * Convenience method to return deprecation information for the class.
     * Acts internally through the {@link Attributes} interface.
     */
    public boolean isDeprecated() {
        return getAttribute(Constants.ATTR_DEPRECATED) != null;
    }

    /**
     * Convenience method to set whether this class should be considered
     * deprecated. Acts internally through the {@link Attributes} interface.
     */
    public void setDeprecated(boolean on) {
        if (!on)
            removeAttribute(Constants.ATTR_DEPRECATED);
        else if (!isDeprecated())
            addAttribute(Constants.ATTR_DEPRECATED);
    }

    ///////////////////////////////////
    // Implementation of VisitAcceptor
    ///////////////////////////////////

    public void acceptVisit(BCVisitor visit) {
        visit.enterBCClass(this);

        ConstantPool pool = null;
        try {
            pool = _state.getPool();
        } catch (UnsupportedOperationException uoe) {
        }
        if (pool != null)
            pool.acceptVisit(visit);

        BCField[] fields = getDeclaredFields();
        for (int i = 0; i < fields.length; i++) {
            visit.enterBCMember(fields[i]);
            fields[i].acceptVisit(visit);
            visit.exitBCMember(fields[i]);
        }

        BCMethod[] methods = getDeclaredMethods();
        for (int i = 0; i < methods.length; i++) {
            visit.enterBCMember(methods[i]);
            methods[i].acceptVisit(visit);
            visit.exitBCMember(methods[i]);
        }

        visitAttributes(visit);
        visit.exitBCClass(this);
    }

    ////////////////////////////////
    // Implementation of Attributes
    ////////////////////////////////

    public Project getProject() {
        return _project;
    }

    public ConstantPool getPool() {
        return _state.getPool();
    }

    public ClassLoader getClassLoader() {
        if (_loader != null)
            return _loader;
        return Thread.currentThread().getContextClassLoader();
    }

    public boolean isValid() {
        return _project != null;
    }

    Collection getAttributesHolder() {
        return _state.getAttributesHolder();
    }

    ///////////////////////////////
    // Implementation of Annotated
    ///////////////////////////////

    BCClass getBCClass() {
        return this;
    }

    /**
     * Attempts to change the class name with the owning project. The project
     * can reject the change if a class with the given new name already
     * exists; therefore this method should be called before the change is
     * recorded in the class.
     */
    private void beforeRename(String oldName, String newName) {
        if ((_project != null) && (oldName != null))
            _project.renameClass(oldName, newName, this);
    }
}
